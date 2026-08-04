package linxcore.ooo

import org.scalatest.funsuite.AnyFunSuite

class OooParamIdentityDomainSpec extends AnyFunSuite {
  test("ROB member and recipe uop identities retain unequal namespaces") {
    val p = OooParams(
      robIdentityMembersPerGroup = 4,
      maxOrdinaryUopsPerGroup = 4,
      maxRecipeUops = 32,
      uopIdentityEntriesPerInstruction = 32)
    val member = new RobMemberKey(p)
    val uop = new CanonicalUopKey(p)

    assert(p.robMemberIndexWidth == 2)
    assert(p.robMemberCountWidth == 3)
    assert(p.recipeUopIndexWidth == 5)
    assert(p.recipeUopCountWidth == 6)
    assert(member.memberIndex.getWidth == 2)
    assert(uop.uopOrdinal.getWidth == 5)
    assert(uop.uopCount.getWidth == 6)
  }
}
