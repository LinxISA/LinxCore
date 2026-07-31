package linxcore.params

final case class CoreParams(
    widths: WidthParams = WidthParams(),
    ifu: IFUParams = IFUParams(),
    ctu: CTUParams = CTUParams(),
    ooo: OOOParams = OOOParams(),
    iex: IEXParams = IEXParams(),
    lsu: LSUParams = LSUParams(),
    dtu: DTUParams = DTUParams(),
    pcWidth: Int = 64,
    instructionWidth: Int = 64,
    archRegWidth: Int = 6,
    lsidWidth: Int = 32) {
  ParamChecks.validate(this)
}
