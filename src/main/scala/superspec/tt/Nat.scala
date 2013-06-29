package superspec.tt

import mrsc.core._
import superspec._

trait NatAST extends CoreAST {
  case object Zero extends CTerm
  case class Succ(e: CTerm) extends CTerm

  case object Nat extends ITerm
  case class NatElim(a: CTerm, b: CTerm, c: CTerm, d: CTerm) extends ITerm

  case object VNat extends Value
  case object VZero extends Value
  case class VSucc(v: Value) extends Value

  case class NNatElim(m: Value, caseZ: Value, caseS: Value, n: Neutral) extends Neutral
}

trait NatSubst extends CoreSubst with NatAST {
  override def findSubst0(from: CTerm, to: CTerm): Option[Subst] = (from, to) match {
    case (Zero, Zero) =>
      Some(Map())
    case (Succ(x1), Succ(x2)) =>
      findSubst0(x1, x2)
    case _ =>
      super.findSubst0(from, to)
  }

  override def findSubst0(from: ITerm, to: ITerm): Option[Subst] = (from, to) match {
    case (Nat, Nat) =>
      Some(Map())
    case (NatElim(m1, zCase1, sCase1, n1), NatElim(m2, zCase2, sCase2, n2)) =>
      mergeOptSubst(
        findSubst0(m1, m2),
        findSubst0(zCase1, zCase2),
        findSubst0(sCase1, sCase2),
        findSubst0(n1, n2)
      )
    case _ =>
      super.findSubst0(from, to)
  }

  override def isFreeSubTerm(t: CTerm, depth: Int): Boolean = t match {
    case Zero =>
      true
    case Succ(c) =>
      isFreeSubTerm(c, depth)
    case _ =>
      super.isFreeSubTerm(t, depth)
  }

  override def isFreeSubTerm(t: ITerm, depth: Int): Boolean = t match {
    case Nat =>
      true
    case NatElim(a, b, c, d) =>
      isFreeSubTerm(a, depth) && isFreeSubTerm(b, depth) &&
        isFreeSubTerm(c, depth) && isFreeSubTerm(d, depth)
    case _ =>
      super.isFreeSubTerm(t, depth)
  }
}

trait NatPrinter extends CorePrinter with NatAST {
  override def iPrint(p: Int, ii: Int, t: ITerm): Doc = t match {
    case Nat =>
      "Nat"
    case NatElim(m, z, s, n) =>
      iPrint(p, ii, Free(Global("natElim")) @@ m @@ z @@ s @@ n)
    case _ => super.iPrint(p, ii, t)
  }

  override def cPrint(p: Int, ii: Int, t: CTerm): Doc = t match {
    case Zero =>
      iPrint(p, ii, Free(Global("Zero")))
    case Succ(n) =>
      iPrint(p, ii, Free(Global("Succ")) @@ n)
    case _ => super.cPrint(p, ii, t)
  }

  def fromNat(n: Int, ii: Int, t: CTerm): Doc = t match {
    case Zero =>
      "Zero"
    case Succ(k) =>
      fromNat(n + 1, ii, k)
    case _ =>
      parens(n.toString <> " + " <> cPrint(3, ii, t))
  }
}

trait NatEval extends CoreEval with NatAST {
  override def eval(t: CTerm, named: NameEnv[Value], bound: Env): Value = t match {
    case Zero =>
      VZero
    case Succ(n) =>
      VSucc(eval(n, named, bound))
    case _ => super.eval(t, named, bound)
  }

  override def eval(t: ITerm, named: NameEnv[Value], bound: Env): Value = t match {
    case Nat =>
      VNat
    case NatElim(m, mz, ms, n) =>
      val mzVal = eval(mz, named, bound)
      val msVal = eval(ms, named, bound)
      def rec(nVal: Value): Value = nVal match {
        case VZero =>
          mzVal
        case VSucc(k) =>
          vapp(vapp(msVal, k), rec(k))
        case VNeutral(n) =>
          VNeutral(NNatElim(eval(m, named, bound), mzVal, msVal, n))
      }
      rec(eval(n, named, bound))
    case _ =>
      super.eval(t, named, bound)
  }
}

trait NatDriver extends CoreDriver with NatAST {

  // boilerplate/indirections
  case object SuccLabel extends Label
  case class SuccStep(next: CTerm) extends Step {
    override val graphStep =
      AddChildNodesStep[Conf, Label](List(DConf(next, Inf(Nat)) -> SuccLabel))
  }
  case class SuccDStep(next: CTerm) extends DriveStep {
    override def step(t: Conf) = SuccStep(next)
  }

  override def driveNeutral(n: Neutral): DriveStep = n match {
    case natElim: NNatElim =>
      natElim.n match {
        case NFree(n) =>
          val caseZ = ElimBranch(Zero, Map())
          val n1 = freshName(Inf(Nat))
          val v1 = Inf(Free(n1))
          val caseS = ElimBranch(Succ(v1), Map(n1 -> Inf(Free(n))))
          ElimDStep(n, List(caseZ, caseS))
        case n =>
          driveNeutral(n)
      }
    case _ =>
      super.driveNeutral(n)
  }

  override def elimFreeVar(c: Conf, fv: Local): List[ElimDStep] = typeMap(fv) match {
    case Inf(Nat) =>
      val caseZ = ElimBranch(Zero, Map())
      val n1 = freshName(Inf(Nat))
      val v1 = Inf(Free(n1))
      val caseS = ElimBranch(Succ(v1), Map(n1 -> Inf(Free(fv))))
      List(ElimDStep(fv, List(caseZ, caseS)))
    case _ =>
      super.elimFreeVar(c, fv)
  }

  override def decompose(c: Conf): DriveStep = c.ct match {
    case Succ(c1) =>
      val Inf(Nat) = c.tp
      SuccDStep(c1)
    case _ =>
      super.decompose(c)
  }

}

trait NatResiduator extends BaseResiduator with NatDriver {
  override def fold(g: TG, node: N, env: NameEnv[Value], recM: Map[CTerm, Value], tp: Value): Value =
    node.outs match {
      case
        TEdge(nodeZ, CaseBranchLabel(sel, ElimBranch(Zero, _))) ::
          TEdge(nodeS, CaseBranchLabel(_, ElimBranch(Succ(Inf(Free(fresh))), _))) ::
          Nil =>
        val motive =
          VLam(n => eval(quote0(tp), env + (sel -> n), Nil))
        val zType =
          eval(quote0(tp), env + (sel -> VZero), Nil)
        val zCase =
          fold(g, nodeZ, env, recM, zType)
        val sType = eval(quote0(tp), env + (sel -> VSucc(vfree(fresh))), Nil)
        val sCase =
          VLam {n => VLam {rec =>
            fold(g, nodeS, env + (fresh -> n), recM + (node.conf.ct -> rec), sType)
          }}
        VNeutral(NFree(Global("natElim"))) @@ motive @@ zCase @@ sCase @@ env(sel)
      case TEdge(n1, SuccLabel) :: Nil =>
        VNeutral(NFree(Global("Succ"))) @@ fold(g, n1, env, recM, tp)
      case _ =>
        super.fold(g, node, env, recM, tp)
    }
}

trait NatCheck extends CoreCheck with NatAST {
  override def iType(i: Int, named: NameEnv[Value], bound: NameEnv[Value], t: ITerm): Result[Value] = t match {
    case Nat =>
      Right(VStar)
    case NatElim(m, mz, ms, n) =>
      val z = for {
        _ <- cType(i, named, bound, m, VPi(VNat, x => VStar)).right.toOption
        mVal = eval(m, named, Nil)
        _ <- cType(i, named, bound, mz, vapp(mVal, VZero)).right.toOption
        _ <- cType(i, named, bound, ms, VPi(VNat, k => VPi(vapp(mVal, k), x => vapp(mVal, VSucc(k))))).right.toOption
        _ <- cType(i, named, bound, n, VNat).right.toOption
        nVal = eval(n, named, Nil)
      } yield
        vapp(mVal, nVal)
      z match {
        case Some(e) => Right(e)
        case None => Left("")
      }
    case _ =>
      super.iType(i, named, bound, t)
  }

  override def cType(ii: Int, named: NameEnv[Value], bound: NameEnv[Value], ct: CTerm, t: Value): Result[Unit] = (ct, t) match {
    case (Zero, VNat) =>
      Right(())
    case (Succ(k), VNat) =>
      cType(ii, named, bound, k, VNat)
    case _ =>
      super.cType(ii, named, bound, ct, t)
  }

  override def iSubst(i: Int, r: ITerm, it: ITerm): ITerm = it match {
    case Nat =>
      Nat
    case NatElim(m, mz, ms, n) =>
      NatElim(cSubst(i, r, m), cSubst(i, r, mz), cSubst(i, r, ms), cSubst(i, r, n))
    case _ => super.iSubst(i, r, it)
  }

  override def cSubst(ii: Int, r: ITerm, ct: CTerm): CTerm = ct match {
    case Zero =>
      Zero
    case Succ(n) =>
      Succ(cSubst(ii, r, n))
    case _ =>
      super.cSubst(ii, r, ct)
  }

}

trait NatQuote extends CoreQuote with NatAST {
  override def quote(ii: Int, v: Value): CTerm = v match {
    case VNat => Inf(Nat)
    case VZero => Zero
    case VSucc(n) => Succ(quote(ii, n))
    case _ => super.quote(ii, v)
  }
  override def neutralQuote(ii: Int, n: Neutral): ITerm = n match {
    case NNatElim(m, z, s, n) =>
      NatElim(quote(ii, m), quote(ii, z), quote(ii, s), Inf(neutralQuote(ii, n)))
    case _ => super.neutralQuote(ii, n)
  }
}

trait NatREPL extends CoreREPL with NatAST with NatPrinter with NatCheck with NatEval with NatQuote {
  lazy val natTE: NameEnv[Value] =
    Map(
      Global("Zero") -> VNat,
      Global("Succ") -> VPi(VNat, _ => VNat),
      Global("Nat") -> VStar,
      Global("natElim") -> natElimType
    )
  val natElimTypeIn =
    """
      |forall (m :: forall x :: Nat . *) .
      |forall (zCase :: m Zero) .
      |forall (sCase :: forall (n :: Nat) . forall (a :: m n) . m (Succ n)) .
      |forall (n :: Nat) .
      |m n
    """.stripMargin
  lazy val natElimType = int.ieval(natVE, int.parseIO(int.iParse, natElimTypeIn).get)
  val natVE: NameEnv[Value] =
    Map(
      Global("Zero") -> VZero,
      Global("Succ") -> VLam(n => VSucc(n)),
      Global("Nat") -> VNat,
      Global("natElim") ->
        eval(
          Lam(Lam(Lam(Lam(
            Inf(NatElim(Inf(Bound(3)), Inf(Bound(2)), Inf(Bound(1)), Inf(Bound(0))))
          )))),
          emptyNEnv, Nil)
    )

  def toNat1(n: Int): CTerm =
    if (n == 0) Zero else Succ(toNat1(n - 1))

  override def toNat(i: Int): ITerm =
    Ann(toNat1(i), Inf(Nat))
}
