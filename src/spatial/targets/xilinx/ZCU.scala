package spatial.targets
package xilinx

import models._

object ZCU extends XilinxDevice {
  import XilinxDevice._
  val name = "ZCU"
  def burstSize = 512

  // TODO: Cut resource counts in half to make hypermapper work harder on smaller apps for R&D reasons
  override def capacity: Area = Area(
    SLICEL -> 34260 / 2,  // Can use any LUT
    SLICEM -> 17600 / 2,  // Can only use specialized LUTs
    Slices -> 34260 / 2,  // SLICEL + SLICEM
    Regs   -> 548160 / 2,
    BRAM   -> 912 / 2,    // 1 RAM36 or 2 RAM18s
    DSPs   -> 2520 / 2
  )
}
