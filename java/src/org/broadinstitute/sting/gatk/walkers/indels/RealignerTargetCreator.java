package org.broadinstitute.sting.gatk.walkers.indels;

import net.sf.samtools.*;
import org.broadinstitute.sting.gatk.refdata.*;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.filters.Platform454Filter;
import org.broadinstitute.sting.gatk.filters.ZeroMappingQualityReadFilter;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.pileup.*;
import org.broadinstitute.sting.gatk.walkers.ReadFilters;
import org.broadinstitute.sting.utils.cmdLine.Argument;

import java.util.*;

/**
 * Emits intervals for the Local Indel Realigner to target for cleaning.  Ignores 454 and MQ0 reads.
 */
@ReadFilters({Platform454Filter.class, ZeroMappingQualityReadFilter.class})
public class RealignerTargetCreator extends LocusWalker<RealignerTargetCreator.Event, RealignerTargetCreator.Event> {

    // mismatch/entropy/SNP arguments
    @Argument(fullName="windowSize", shortName="window", doc="window size for calculating entropy or SNP clusters", required=false)
    protected int windowSize = 10;

    @Argument(fullName="mismatchFraction", shortName="mismatch", doc="fraction of base qualities needing to mismatch for a position to have high entropy; to disable set to <= 0 or > 1", required=false)
    protected double mismatchThreshold = 0.15;

    @Argument(fullName="minReadsAtLocus", shortName="minReads", doc="minimum reads at a locus to enable using the entropy calculation", required=false)
    protected int minReadsAtLocus = 4;

    // interval merging arguments
    @Argument(fullName="maxIntervalSize", shortName="maxInterval", doc="maximum interval size", required=false)
    protected int maxIntervalSize = 500;

    @Override
    public boolean generateExtendedEvents() { return true; }

    @Override
    public boolean includeReadsWithDeletionAtLoci() { return true; }


    public void initialize() {
        if ( windowSize < 2 )
            throw new StingException("Window Size must be an integer greater than 1");
    }

    public Event map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {

        boolean hasIndel = false;
        boolean hasInsertion = false;
        boolean hasPointEvent = false;

        long furthestStopPos = -1;

        // look for insertions in the extended context (we'll get deletions from the normal context)
        if ( context.hasExtendedEventPileup() ) {
            ReadBackedExtendedEventPileup pileup = context.getExtendedEventPileup();
            if ( pileup.getNumberOfInsertions() > 0 ) {
                hasIndel = hasInsertion = true;
                // check the ends of the reads to see how far they extend
                for (ExtendedEventPileupElement p : pileup )
                    furthestStopPos = Math.max(furthestStopPos, p.getRead().getAlignmentEnd());
            }
        }

        // look at the rods for indels or SNPs
        if ( tracker != null ) {
             Iterator<ReferenceOrderedDatum> rods = tracker.getAllRods().iterator();
             while ( rods.hasNext() ) {
                 ReferenceOrderedDatum rod = rods.next();
                 if ( rod instanceof VariationRod ) {
                     if ( ((VariationRod)rod).isIndel() ) {
                         hasIndel = true;
                         if ( ((VariationRod)rod).isInsertion() )
                            hasInsertion = true;
                     }
                     if ( ((VariationRod)rod).isSNP() )
                         hasPointEvent = true;
                 }
             }
        }

        // look at the normal context to get deletions and positions with high entropy
        ReadBackedPileup pileup = context.getBasePileup();
        if ( pileup != null ) {

            int mismatchQualities = 0, totalQualities = 0;
            char upperRef = Character.toUpperCase(ref.getBase());
            for (PileupElement p : pileup ) {
                // check the ends of the reads to see how far they extend
                SAMRecord read = p.getRead();
                furthestStopPos = Math.max(furthestStopPos, read.getAlignmentEnd());

                // is it a deletion? (sanity check in case extended event missed it)
                if ( p.isDeletion() ) {
                    hasIndel = true;
                }

                // look for mismatches
                else {
                    if ( Character.toUpperCase(p.getBase()) != upperRef )
                        mismatchQualities += p.getQual();
                    totalQualities += p.getQual();
                }
            }

            // make sure we're supposed to look for high entropy
            if ( mismatchThreshold > 0.0 &&
                    mismatchThreshold <= 1.0 &&
                    pileup.size() >= minReadsAtLocus &&
                    (double)mismatchQualities / (double)totalQualities >= mismatchThreshold )
                hasPointEvent = true;
        }

        if ( !hasIndel && !hasPointEvent )
            return null;

        GenomeLoc eventLoc = context.getLocation();
        if ( hasInsertion )
            eventLoc =  GenomeLocParser.createGenomeLoc(eventLoc.getContigIndex(), eventLoc.getStart(), eventLoc.getStart()+1);

        EVENT_TYPE eventType = (hasIndel ? (hasPointEvent ? EVENT_TYPE.BOTH : EVENT_TYPE.INDEL_EVENT) : EVENT_TYPE.POINT_EVENT);

        return new Event(eventLoc, furthestStopPos, eventType);
    }

    public void onTraversalDone(Event sum) {
        if ( sum != null && sum.isReportableEvent() )
            out.println(sum.toString());
    }

    public Event reduceInit() {
        return null;
    }

    public Event reduce(Event value, Event sum) {
        // ignore no new events
        if ( value == null )
            return sum;

        // if it's the first good value, use it
        if ( sum == null )
            return value;

        // if we hit a new contig or they have no overlapping reads, then they are separate events - so clear sum
        if ( sum.loc.getContigIndex() != value.loc.getContigIndex() || sum.furthestStopPos < value.loc.getStart() ) {
            if ( sum.isReportableEvent() )
                out.println(sum.toString());
            return value;
        }

        // otherwise, merge the two events
        sum.merge(value);
        return sum;
    }

    private enum EVENT_TYPE { POINT_EVENT, INDEL_EVENT, BOTH }

    class Event {
        public long furthestStopPos;

        public GenomeLoc loc;
        public long eventStartPos;
        private long eventStopPos;
        private EVENT_TYPE type;
        private ArrayList<Long> pointEvents = new ArrayList<Long>();

        public Event(GenomeLoc loc, long furthestStopPos, EVENT_TYPE type) {
            this.loc = loc;
            this.furthestStopPos = furthestStopPos;
            this.type = type;

            if ( type == EVENT_TYPE.INDEL_EVENT || type == EVENT_TYPE.BOTH ) {
                eventStartPos = loc.getStart();
                eventStopPos = loc.getStop();
            } else {
                eventStartPos = -1;
                eventStopPos = -1;
            }

            if ( type == EVENT_TYPE.POINT_EVENT || type == EVENT_TYPE.BOTH ) {
                pointEvents.add(loc.getStart());
            }
        }

        public void merge(Event e) {

            // merges only get called for events with certain types
            if ( e.type == EVENT_TYPE.INDEL_EVENT || e.type == EVENT_TYPE.BOTH ) {
                if ( eventStartPos == -1 )
                    eventStartPos = e.eventStartPos;
                eventStopPos = e.eventStopPos;
                furthestStopPos = e.furthestStopPos;
            }

            if ( e.type == EVENT_TYPE.POINT_EVENT || e.type == EVENT_TYPE.BOTH ) {
                long newPosition = e.pointEvents.get(0);
                if ( pointEvents.size() > 0 ) {
                    long lastPosition = pointEvents.get(pointEvents.size()-1);
                    if ( newPosition - lastPosition < windowSize ) {
                        eventStopPos = Math.max(eventStopPos, newPosition);
                        furthestStopPos = e.furthestStopPos;

                        if ( eventStartPos == -1 )
                            eventStartPos = lastPosition;
                        else
                            eventStartPos = Math.min(eventStartPos, lastPosition);
                    }
                }
                pointEvents.add(newPosition);
            }
        }

        public boolean isReportableEvent() {
            return eventStartPos >= 0 && eventStopPos >= 0 && eventStopPos - eventStartPos < maxIntervalSize;
        }

        public String toString() {
            return String.format("%s:%d-%d", loc.getContig(), eventStartPos, eventStopPos);
        }
    }
}