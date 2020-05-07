package fpu

import chisel3._
import chisel3.util._
import fpu.util.ORTree

class IntToFloat extends FPUSubModule with HasPipelineReg {
  def latency = 2

  /** Stage 1: Count leading zeros and shift
    */

  val op = io.in.bits.op
  val isDouble = io.in.bits.isDouble
  val a = io.in.bits.a
  val aNeg = -a
  val aSign = Mux(op(0), false.B, Mux(op(1), a(63), a(31)))
  val aVal = Mux(aSign, Mux(op(1), aNeg, aNeg(31, 0)), Mux(op(1), a, a(31, 0)))
  val leadingZeros = PriorityEncoder(aVal.asBools().reverse)

  // exp = xlen - 1 - leadingZeros + bias
  val expUnrounded = S1Reg(
    Mux(isDouble,
      (64 - 1 + Float64.expBiasInt).U - leadingZeros,
      (64 - 1 + Float32.expBiasInt).U - leadingZeros
    )
  )
  val rmReg = S1Reg(io.in.bits.rm)
  val opReg = S1Reg(op)
  val isDoubleReg = S1Reg(isDouble)
  val aIsZeroReg = S1Reg(a===0.U)
  val aSignReg = S1Reg(aSign)
  val aShifted = S1Reg(aVal << leadingZeros)

  /** Stage 2: Rounding
    */
  val mantD = aShifted(62, 62-51)
  val mantS = aShifted(62, 62-22)

  val g = Mux(isDoubleReg, aShifted(62-52), aShifted(62-23))
  val r = Mux(isDoubleReg, aShifted(62-53), aShifted(62-24))
  val s = Mux(isDoubleReg, ORTree(aShifted(62-54, 0)), ORTree(aShifted(62-25, 0)))

  val roudingUnit = Module(new RoundingUnit(Float64.mantWidth))
  roudingUnit.io.in.rm := rmReg
  roudingUnit.io.in.mant := Mux(isDoubleReg, mantD, mantS)
  roudingUnit.io.in.sign := aSignReg
  roudingUnit.io.in.guard := g
  roudingUnit.io.in.round := r
  roudingUnit.io.in.sticky := s

  val mantRounded = roudingUnit.io.out.mantRounded
  val expRounded = Mux(isDoubleReg,
    expUnrounded + roudingUnit.io.out.mantCout,
    expUnrounded + mantRounded(Float32.mantWidth)
  )

  val resS = Cat(
    aSignReg,
    expRounded(Float32.expWidth-1, 0),
    mantRounded(Float32.mantWidth-1, 0)
  )
  val resD = Cat(aSignReg, expRounded, mantRounded)

  io.out.bits.result := S2Reg(Mux(aIsZeroReg, 0.U, Mux(isDoubleReg, resD, resS)))
  io.out.bits.fflags.inexact := S2Reg(roudingUnit.io.out.inexact)
  io.out.bits.fflags.underflow := false.B
  io.out.bits.fflags.overflow := false.B
  io.out.bits.fflags.infinite := false.B
  io.out.bits.fflags.invalid := false.B
}
