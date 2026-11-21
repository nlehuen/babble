package org.babblelang.axioms.lombok;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.MeansNonNull;

@Axioms(targetName = "lombok.NonNull")
@MeansNonNull
public final class NonNullAxioms {
    private NonNullAxioms() {
    }
}
