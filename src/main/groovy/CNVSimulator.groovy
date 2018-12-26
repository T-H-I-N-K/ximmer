import java.util.logging.Level;

import gngs.*

import groovy.transform.CompileStatic
import groovy.util.logging.Log;
import groovyx.gpars.GParsPool;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMTagUtil;
import ximmer.*

/**
 * Produce a BAM file that simulates the presence of a heterozygous deletion in
 * the X chromosome of a given female sample, by substituting reads from a male 
 * sample in a specified region.
 * <p>
 * A number of issues need to be dealt with to make this realistic:
 * 
 * <li>The coverage of the two samples must be normalised to account for the library size
 * <li>Breaking the coverage in a region where there are reads will cause a discontinuity.
 *     Therefore it is important to detect the regions of no coverage so as to 
 *     perform the "swap" in a location where no artefactual read discontinuities will occur.
 * 
 * @author simon.sadedin@mcri.edu.au
 */
@Log
class CNVSimulator {
    
    /**
     * Chromosomes that will be ignored for calculates that are only performed
     * on diploid chromosomes
     */
    public static List<String> NON_AUTOSOMES = ["chrX",  "chrY", "chrM"]

    private static final int MAX_REGION_SELECTION_TRIES = 7i
    
    SAM maleBam 
    
    SAM femaleBam 
    
    /**
     * Fraction of female reads to use from X chromosome
     * Value < 0 means not calculated yet.
     */
    double femaleDownSampleRate = -1.0d
    
    /**
     * Fraction of male reads to use from region to be imported to female 
     * alignment
     * Value < 0 means not calculated yet.
     */
    double maleDownSampleRate = -1.0d
    
    /**
     * Maximum number of attempts to select a random region satisfying all constraints
     */
    int maxSelectionAttempts = MAX_REGION_SELECTION_TRIES
    
    /**
     * Default concurrency used for some multithreadded parts
     */
    int concurrency = 2
    
    /**
     * The kind of simulation to do. One of "replace" or "downsample"
     */
    String simulationMode="replace"
    
    /**
     * If desired the sample id in the simulated target can be different to that
     * in the source files
     */
    String simulatedSampleId
    
    DGV dgv = null
    
    float maxDGVFreq = 0.01
    
    /**
     * The mean coverage to be targeted in the output samples.
     * This value is an optional input parameter. If provided,
     * the coverage.
     */
    private double targetCoverage = -1.0d
    
    /**
     * If targetCoverage is specified, the regions over which it is calculated
     */
    private Regions targetRegions = null
    
    
    /**
     * Random number generator to use for downsampling. The user can set it to
     * predetermine the seed or use different sampling strategy.
     */
    Random random = null
    
    public CNVSimulator(Regions targetRegions, String femaleBam, String maleBam) {
        this(targetRegions, new SAM(femaleBam), maleBam != null ? new SAM(maleBam) : null)
    }
    
    public CNVSimulator(Regions targetRegions, SAM femaleBam, SAM maleBam) {
        this.targetRegions = targetRegions
        this.maleBam = maleBam
        this.femaleBam = femaleBam
        if(random == null)
            this.random = new Random()
    }
    
    void setTargetCoverage(double value) {
        this.targetCoverage = value
        if(random == null)
            this.random = new Random()
    }
    
    static void main(String [] args) {
        
        Utils.configureSimpleLogging()
        
        println "=" * 100
        println "CNV Simulator " + new Date().toString()
        println "=" * 100
        
        Cli cli = new Cli(usage:"CNVSimulator [options]")
        cli.with {
            f "female sample", longOpt: "female", args:1, required:true
            m "male sample", longOpt: "male", args:1, required:true
            t "Number of threads to use", args:1 
            region "region to turn into a CNV", args:1, required:true
            cov "target coverage for output samples (optional)", args:1
            bed "BED file describing covered regions (required for -cov), eg: Amplicon bed file for HaloPlex", args:1
            o "output file", args:1, required:true
            seed "Random seed, specify to make simulations reproducible", args:1
            mode "Method for simulating deletions: downsampling reads=downsample, replacement from male=replace, both=use both methods (2 output files)", args:1
        }
        
        def opts = cli.parse(args)
        if(!opts) 
            System.exit(1)
            
        def simulator = new CNVSimulator(opts.f, opts.m)
        
        if(opts.t)
            simulator.concurrency = opts.t.toInteger()
            
        if(opts.seed) 
            simulator.random = new Random(opts.seed.toLong())
        
        if(opts.cov) {
            if(!opts.bed) {
                System.err.println "Please provide option -bed with the amplicon BED file, to specify regions to calculate coverage over"
                System.exit(1)
            }
            simulator.setTargetCoverage(new BED(opts.bed).load(), opts.cov.toDouble())  
        }
        
        def simulationModes = ["replace","downsample"]
        if(opts.mode) { 
            if(!(opts.mode in simulationModes)) {
                System.err.println "Please specify one of " + simulationModes + " for 'mode' option"
                System.exit()
            }
                
            simulator.simulationMode = opts.mode
        }
        
        simulator.createBam(opts.o, new Regions().addRegion(new Region(opts.region)))
    }
    
    @CompileStatic
    int countAutosomeReads(SAM bam) {
        int count=0
        bam.eachRecord { SAMRecord r ->
            if(NON_AUTOSOMES.contains(r.referenceName))
                return
            ++count    
        }
        return count
    }
    
    void calculateDownsampleRates() {
        
        // Default is not to downsample - which one we downsample
        // depends on which one is higher to start with
        femaleDownSampleRate = 1.0d
        maleDownSampleRate = 1.0d
        
        // If there is no male bam and there is not a specific target coverage set,
        // then down sampling is not necessary
        if(maleBam == null && targetCoverage <= 0.0d)
            return
        
        GParsPool.withPool(this.concurrency) {
            log.info "Using $concurrency threads to calculate downsample rates"
            if(this.targetCoverage > 0.0d) {
                Regions autosomalRegions = null
                synchronized(this) { // cause write flush after we build this block
                    autosomalRegions = this.targetRegions.reduce().grep { !NON_AUTOSOMES.contains(it.chr) }
                }
            
                List femaleStats = null
                List maleStats = null
                log.info "Determining coverage of female autosomal regions ..."
                
                ProgressCounter counter = new ProgressCounter()
                femaleStats = autosomalRegions.collectParallel { counter.count(); femaleBam.coverageStatistics(it.chr, it.from, it.to); }
                
                double femaleSum = (femaleStats.sum { it.mean * it.n })
                double femaleMean = femaleSum / femaleStats.sum { it.n }
                this.femaleDownSampleRate = this.targetCoverage / femaleMean
                log.info "Female coverage = $femaleMean: downsampling reads by $femaleDownSampleRate"
                
                log.info "Determining coverage of male autosomal regions ..."
                if(maleBam != null) {
                    maleStats = autosomalRegions.collectParallel { counter.count(); maleBam.coverageStatistics(it.chr, it.from, it.to); }
                    double maleMean = maleStats.sum { it.mean * it.n } / maleStats.sum { it.n }
                    this.maleDownSampleRate = this.targetCoverage / maleMean
                    log.info "Male coverage = $maleMean: downsampling reads by $maleDownSampleRate"
                }
            }
            else {
                
                int maleReadCount = countAutosomeReads(maleBam)
                int femaleReadCount = countAutosomeReads(femaleBam)
                
                
                if(maleReadCount < femaleReadCount) {
                    // Female sample is too high, so downsample the female sample
                    femaleDownSampleRate = ((float)maleReadCount) / ((float)femaleReadCount)
                }
                else {
                    // Male sample is too high, so downsample the male sample
                    maleDownSampleRate = ((float)femaleReadCount) / ((float)maleReadCount)
                }
                
                println "Male reads ($maleBam.samFile)  " + maleReadCount + " downsample rate = " + maleDownSampleRate
                println "Female reads ($femaleBam.samFile)  " + femaleReadCount + " downsample rate = " + femaleDownSampleRate
            }
        }
    }
    
    void createBam(String outputFileName, Regions regions) {
        
        calculateDownsampleRates()
        
        if(this.simulationMode=="replace") {
            this.createBamByReplacement(outputFileName, regions)
        }
        else
        if(this.simulationMode=="downsample") {
            this.createBamByDownSample(outputFileName, regions)
        }
        else
        if(this.simulationMode=="both") {
            this.createBamByReplacement(outputFileName, regions)
            
            def bothName = outputFileName.replaceAll('.bam$','.downsample.bam')
            this.createBamByDownSample(bothName, regions)
            println "Created $bothName"
        }        
        else {
            throw new IllegalArgumentException("Simulation mode of $simulationMode is not recognized. Please use one of [replace,downsample,both]")
        }
        
        log.info "Created $outputFileName"
    }
    
    @CompileStatic
    List<SAMRecordPair> loadMaleReads(Regions cleanRegions) {
        log.info "Querying source reads from Male alignment ..."
        int count = 0
        List<SAMRecordPair> maleRegionReads = []
        for(Region cleanRegion in cleanRegions) { 
            maleBam.eachPair(cleanRegion.chr,cleanRegion.from,cleanRegion.to) { SAMRecord r1, SAMRecord r2 ->
                SAMRecordPair pair = new SAMRecordPair(r1:r1,r2:r2)
                pair.setTag("DL","1") // Set tag to indicate this read is part of a deletion
                maleRegionReads.add(pair)
                ++count
            }
        }
        log.info "Found $count read pairs in region over male alignment " + this.maleBam.samples[0]
        if(count == 0) 
            throw new RuntimeException("Male alignment has no reads over targeted region for CNV")
            
        return maleRegionReads.sort { SAMRecordPair pair -> pair.r1.alignmentStart }
    }
    
    /**
     * Create a BAM file from this simulator's female BAM with regions overlapping
     * cleanRegions substituted from this simulator's male BAM.
     * <p>
     * The two BAM file *must* be sorted in the same order. The output is written
     * preserving the sort order.
     * 
     * @param outputFileName    file to write to
     * @param cleanRegions      regions to replace with reads from male BAM file
     */
    void createBamByReplacement(String outputFileName, Regions cleanRegions) {
        
        log.info "Creating $outputFileName using replace mode"
       
        List<SAMRecordPair> maleRegionReads = loadMaleReads(cleanRegions)
            
        if(this.random == null)
            random = new Random()
        
        final String rgId = femaleBam.samFileReader.fileHeader.getReadGroups()[0].getId()
        
        long outputReadCount = 0;
        
        // Read each BAM 
        ProgressCounter writeProgress = new ProgressCounter()
        writeProgress.extra = {
            " $outputReadCount reads written to $outputFileName"
        }
        
        Iterator<SAMRecordPair> nextMaleRegionReadIterator = maleRegionReads.iterator()
        SAMRecordPair nextMaleReadPair = nextMaleRegionReadIterator.next()
        
        Closure setPairInfo = { SAMRecordPair pair ->
           pair.setReadGroup(rgId)
           ++outputReadCount
        }
            
        Closure flushReads = { actor ->
            log.info "Flushing residual male reads for $outputFileName"
            while(nextMaleRegionReadIterator.hasNext()) {
                if(random.nextFloat() < maleDownSampleRate) {
                   SAMRecordPair pair = nextMaleRegionReadIterator.next()
                   setPairInfo(pair)
                   actor << pair
                }
            }
        }
        
        Map options = [:]
        if(this.simulatedSampleId != null)
            options.sampleId = this.simulatedSampleId
            
        femaleBam.filterOrderedPairs(options + [end: flushReads, spoolSize:8000], outputFileName) { SAMRecordPair pair ->
                
            List<SAMRecordPair> result = []
                 
            nextMaleReadPair = extractMaleReadsUntil(nextMaleReadPair, nextMaleRegionReadIterator, pair, result)
                
            if(!cleanRegions.overlaps(pair.r1.referenceName, pair.r1.alignmentStart, pair.r2.alignmentEnd)) {
                if(random.nextFloat() < femaleDownSampleRate) {
                   result << pair
                }
            }
            writeProgress.count()
                
            result.each(setPairInfo)
                
            return result
        }
        
        writeProgress.end()
    } 
    
    
    @CompileStatic
    SAMRecordPair extractMaleReadsUntil(SAMRecordPair start, Iterator<SAMRecordPair> iter, SAMRecordPair pair, List<SAMRecordPair> result) {
        
       SAMRecordPair nextMaleReadPair = start
        
       // Are there male reads to write out first?
       while(isPairBefore(nextMaleReadPair, pair)) {
            if(random.nextFloat() < maleDownSampleRate) {
               result << nextMaleReadPair
            }
                       
           if(iter.hasNext())
               nextMaleReadPair = iter.next()
           else {
               nextMaleReadPair = null
           }
       }
       return nextMaleReadPair
    }
    
    void createBamByReplacementOld(String outputFileName, Regions cleanRegions) {
        
        log.info "Creating $outputFileName using replace mode"
       
        List<SAMRecordPair> maleRegionReads = loadMaleReads(cleanRegions)
            
        if(this.random == null)
            random = new Random()
        
        final String chr = cleanRegions[0].chr
        final String rgId = femaleBam.samFileReader.fileHeader.getReadGroups()[0].getId()
        
        final long outputReadCount = 0;
        
        // Read each BAM 
        ProgressCounter writeProgress = new ProgressCounter()
        writeProgress.extra = {
            " $outputReadCount reads written to $outputFileName"
        }
        
        femaleBam.withWriter(outputFileName, false) { SAMFileWriter  writer ->
            femaleBam.eachPair { SAMRecord r1, SAMRecord r2 ->
                    
                if(r1 == null|| r2 == null)
                    return
                    
                def boundaries = [r1.alignmentStart, r1.alignmentEnd, r2.alignmentStart, r2.alignmentEnd]
                int rStart = boundaries.min()
                int rEnd = boundaries.max()
                    
                if(cleanRegions.overlaps(chr, rStart, rEnd))
                    return
                    
                // For regions outside that which we are simulating for,
                // downsample the reads to make the mean coverage
                // the same as that from the lower coverage file
                // (the lower coverage file might be this one or the other file that is the source for simulated reads)
                if(random.nextFloat() < femaleDownSampleRate) {
                    writer.addAlignment(r1)
                    writer.addAlignment(r2)
                    outputReadCount+=2
                    writeProgress.count()
                }
            }
                
            for(List<SAMRecord> r in maleRegionReads) {
                if(random.nextFloat() < maleDownSampleRate) {
                    r[0].setAttribute("DL", "1")
                    r[0].setAttribute(SAMTagUtil.getSingleton().RG, rgId);
                    writer.addAlignment(r[0])
                    r[1].setAttribute("DL","1")
                    r[1].setAttribute(SAMTagUtil.getSingleton().RG, rgId);
                    writer.addAlignment(r[1])
                    outputReadCount+=2
                    writeProgress.count()
                }
            }
        }
        writeProgress.end()
    }
    
    /**
     * Returns true if pair1 is not null and has an alignment starting prior to pair2
     * @param pair1
     * @param pair2
     * @return
     */
    @CompileStatic
    boolean isPairBefore(SAMRecordPair pair1, SAMRecordPair pair2) {
        return pair1 && (pair1.r1.referenceIndex == pair2.r1.referenceIndex) && (pair1.r1.alignmentStart < pair2.r1.alignmentStart)        
    }
    
    /**
     * This method creates a BAM file with simulated deletions purely by randomly selecting
     * only 50% of the reads over the CNV region.
     * 
     * @param outputFileName
     * @param region
     */
    @CompileStatic
    void createBamByDownSample(String outputFileName, Regions cleanRegions) {
        
        log.info "Creating $outputFileName using downsample mode"
        
        // Inside regions of simulated deletions, downsample to 50% of the otherwise estimated rate
        // technical note: the femaleDownSampleRate takes into account the coverage in the supplied
        //                 male alignment. This is necessary to make the output comparable
        //                 in terms of coverage when using 'both'.
        double deletionDownSampleRate = 0.5d * femaleDownSampleRate
        
        Map options = [:]
        if(this.simulatedSampleId != null)
            options.sampleId = this.simulatedSampleId
            
        // Read each BAM 
        femaleBam.filterOrderedPairs(options, outputFileName) { SAMRecordPair pair ->
               
            SAMRecord r1 = pair.r1
            SAMRecord r2 = pair.r2
                
            if(r1 == null|| r2 == null)
                return false
                
            List<Integer> boundaries = [r1.alignmentStart, r1.alignmentEnd, r2.alignmentStart, r2.alignmentEnd]
            int rStart = boundaries.min()
            int rEnd = boundaries.max()
                
            double downSampleRate = femaleDownSampleRate
                
            cleanRegions.overlaps(r1.referenceName, rStart, rEnd)
                
            if(cleanRegions.any { cr ->  cr.overlaps(r1.referenceName, rStart, rEnd)}) {
                downSampleRate = deletionDownSampleRate
            }
                
            // For regions outside that which we are simulating for,
            // downsample the reads to make the mean coverage
            // the same as that from the lower coverage file
            // strictly not necesary for a pure downsample simulation, but we want to make
            // output that is comparable with that produced when reads are sourced from a male
            if(random.nextDouble() < downSampleRate) {
                try {
                    return true
                }
                catch(IllegalArgumentException e) {
                    if(e.message.startsWith("Alignments added out of order")) {
                        println "WARNING: failed to add alignment $r1.readName at $r1.referenceName:$r1.alignmentStart, $r2.referenceName:$r2.alignmentStart - is input file sorted?"
                        println "WARNING: Full message is $e.message"
                        throw e
                    }
                    else {
                        throw e
                    }
                }
            }
            else {
                return false
            }
        }
        femaleBam.verbose = false
    }
    
    /**
     * Expand the given region until it is "clean" on either side in both the
     * male and female files.
     * 
     * @param region
     * @return
     */
    Region findCleanRegion(Regions fromRegions, Region seedRegion) {
        
        // Create a search window to expand the regions into
        Regions seedWindow = fromRegions.window(seedRegion, 10).grep { it.chr == seedRegion.chr } as Regions
        
        log.info "Seed region = $seedRegion, search window = " +  seedWindow[0].chr + ":" + seedWindow[0].from + " - " + seedWindow[-1].to 
        
        if(!seedWindow.overlaps(seedRegion)) {
            log.severe "ERROR: window " +  seedWindow[0].chr + ":" + seedWindow[0].from + " - " + seedWindow[-1].to + " does not overlap original seed: " + seedRegion
        }
        
        String chromosome = seedRegion.chr
        
        Regions maleReadRegions 
        if(maleBam != null) {
            SAM maleRegionBam = new SAM(maleBam.samFile)
            try {
                maleReadRegions = maleRegionBam.toPairRegions(chromosome,seedWindow[0].from,seedWindow[-1].to)
            }
            finally {
                maleRegionBam.close()
            }
            log.info "Overlaps of male region with target start with " + maleReadRegions.getOverlaps(seedRegion).take(3).collect { it.from + "-" + it.to }
        }
        
        Regions femaleReadRegions = femaleBam.toPairRegions(chromosome,seedWindow[0].from,seedWindow[-1].to,500)
        if(log.isLoggable(Level.FINE))
            log.info "Overlaps of ${femaleReadRegions.numberOfRanges} female read regions with target start with " + 
                femaleReadRegions.getOverlaps(seedRegion).take(3).collect { it.from + "-" + it.to }
        
        Regions combinedRegions = maleReadRegions ? maleReadRegions.reduce() : new Regions()
        femaleReadRegions.reduce().each { r ->
            combinedRegions.addRegion(r)
        }
        combinedRegions = combinedRegions.reduce()
        
        // Finally, find the overlaps with the desired region
        List<IntRange> overlaps = combinedRegions.getOverlaps(seedRegion)
        if(overlaps.empty) {
            log.info "WARNING: no overlapping reads for deletion seed $seedRegion over samples $femaleBam.samples / ${maleBam?.samples}"
            Regions testRegions = femaleBam.toPairRegions(chromosome,seedWindow[0].from,seedWindow[-1].to,500)
            log.info "Try again: " + testRegions
            return null
        }
    
        // We will start from the end points of this range
        Region result = new Region(chromosome, overlaps*.from.min()..overlaps*.to.max())
        
        int numTargetOverlaps = this.targetRegions.getOverlaps(result).size()
        
        log.info "Original region $seedRegion expanded to $result, spanning $numTargetOverlaps targets [female=" + this.femaleBam.samples[0] + ", male=" + this.maleBam?.samples?.getAt(0) + "]"
        return result
    }
    
    /**
     * Select a candidate region to simulate a deletion from a set of supplied regions, 
     * while avoiding a given set of excluded regions.
     * 
     * @param fromRegions   target regions to consider as candidates for simulating a deletion
     * @param numRanges     number of contiguous ranges to include from fromRegions
     * @param excludRegions regions to exclude from consideration as targets
     * @return
     */
    Region selectRegion(Regions fromRegions, int numRanges, Exclusions excludeRegions=null) {
        
        Region cleanRegion  = null
        int attemptCount = 0
        
        List<String> chromosomes = fromRegions.grep { !Region.isMinorContig(it.chr) }*.chr.unique().sort()
        
        log.info "Chromosomes in target region are " + chromosomes
        
        List<String> suitableChromosomes = chromosomes.grep {  chr ->
            (fromRegions.allRanges[chr].size() > numRanges * 2) 
        }
        
        log.info "Suitable chromosomes for simulation are " + suitableChromosomes
        
        if(suitableChromosomes.isEmpty())
            throw new RuntimeException("The target regions supplied do not have $numRanges regions on any chromosome. Please decrease the size of deletions to simulate.")
        
        String chromosome = suitableChromosomes.size() > 1 ? suitableChromosomes[this.random.nextInt(suitableChromosomes.size()-1)] : suitableChromosomes[0]
        
        log.info "Selected chromosome " + chromosome + " to simulate next deletion, now choosing region"
        
        while(true) {
            // The largest target region we can choose to start from is numRanges before the last target region
            int maxViableRange = fromRegions.allRanges[chromosome].size()-numRanges
            int selectedRange =  (int)Math.floor(random.nextDouble() * maxViableRange) 
            List<Range> regions = fromRegions.allRanges[chromosome][selectedRange..(selectedRange+numRanges-1)] 
            Range r = (regions[0].from)..(regions[-1].to)
            
            log.info "Examining region " + chromosome + ":" + r.from + "-" + r.to + " (${Utils.humanBp(r.size())})"
            
            Region seedRegion = new Region(chromosome,r)
            
            // The seed region is directly derived from the target region
            // The problem is, many capture technologies will capture reads to either side of the
            // target region. This means we need to expand the region either side until we observe 
            // no reads, so that a "clean" swap can be made of reads without causing any artefacts.
            cleanRegion = excludeRegions.tryReserve(seedRegion) { 
                Region candidateCleanRegion = findCleanRegion(fromRegions, seedRegion)
                if(isOverlappingPopulationCNV(candidateCleanRegion))
                    return null
                else
                    return candidateCleanRegion
            }
            
            if(cleanRegion != null)
                return cleanRegion
            
            ++attemptCount
            if(attemptCount > MAX_REGION_SELECTION_TRIES)
                throw new RegionSelectionException("Failed to identify $numRanges continguous non-excluded regions in ${this.femaleBam.samples[0]} after $attemptCount tries")
                
            log.info "Selected range $r.from-$r.to failed one or more exclusion criteria: trying again"
        }
        
        assert false
    }
    
    /**
     * Determine if the given region to check overlaps any regions that have been excluded.
     * <p>
     * Regions are excluded if they
     * <li>overlap a region already selected to simulate a CNV in
     * <li>overlap a region where there are known population CNVs,
     *     determined using the {@link #maxDGVFreq} setting.
     * 
     * @param excludeRegions
     * @param cleanRegion
     * @return  true if the given region should be excluded
     */
    boolean isOverlappingPopulationCNV(Region regionToCheck) {
        
        if(dgv == null)
            return false

        float regionCNVFreq = dgv.maxFreq(regionToCheck)
        if(regionCNVFreq < this.maxDGVFreq) {
            return false
        }
        else {
            log.info "Region $regionToCheck overlaps a region in DGV with CNV frequency $regionCNVFreq > $maxDGVFreq"
            return true
        }
        
        assert false
    }
}
