import "examples/nat.hs";
import "examples/eq.hs";

-- proof of the associativity of addition
-- plus x (plus y z) = plus (plus x y) z

$x :: Nat;
$y :: Nat;
$z :: Nat;

e1 = (plus $x (plus $y $z));
e2 = (plus (plus $x $y) $z);
(res1, proof1) = sc e1;
(res2, proof2) = sc e2;

-- associativity of addition using combinators
-- check that t1 and t2 are supercompiled into the same expression
eq_res1_res2 :: Eq Nat res1 res2;
eq_res1_res2 = Refl Nat res1;
-- deriving equality
eq_e1_e2 :: Eq Nat e1 e2;
eq_e1_e2 =
    proof_by_sc Nat e1 e2 res1 proof1 proof2;