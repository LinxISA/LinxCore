package linxcore.params

final case class IEXParams(
    issueWidth: Int = 4,
    aluPipes: Int = 2,
    bruPipes: Int = 1,
    aguPipes: Int = 2,
    stdPipes: Int = 2,
    systemMulticycleQueues: Int = 1,
    cmdIssueQueues: Int = 1,
    scalarIssueEntries: Int = 64,
    scalarIssueBanks: Int = 2,
    integerReadPorts: Int = 6,
    integerWritePorts: Int = 5)
