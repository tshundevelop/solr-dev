public class EvaluationResult {
    private double coverage;
    private double mrr;
    private double lrap;
    private double averageMrrAndLrap;

    public EvaluationResult(double coverage, double mrr, double lrap, double averageMrrAndLrap) {
        this.coverage = coverage;
        this.mrr = mrr;
        this.lrap = lrap;
        this.averageMrrAndLrap = averageMrrAndLrap;
    }
    
    public double getCoverage() { return coverage; }

    public double getMrr() { return mrr; }

    public double getAverageMrrAndLrap() { return averageMrrAndLrap; }

    public double getLrap() { return lrap; }
}
