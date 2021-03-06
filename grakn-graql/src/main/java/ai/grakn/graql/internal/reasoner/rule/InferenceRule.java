/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.internal.reasoner.rule;

import ai.grakn.GraknGraph;
import ai.grakn.concept.Rule;
import ai.grakn.graql.VarName;
import ai.grakn.graql.admin.Atomic;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.PatternAdmin;
import ai.grakn.graql.admin.VarAdmin;
import ai.grakn.graql.internal.pattern.Patterns;
import ai.grakn.graql.internal.reasoner.atom.Atom;
import ai.grakn.graql.internal.reasoner.atom.AtomicFactory;
import ai.grakn.graql.internal.reasoner.atom.binary.Relation;
import ai.grakn.graql.internal.reasoner.atom.predicate.Predicate;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueryImpl;
import com.google.common.collect.Sets;
import javafx.util.Pair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 *
 * <p>
 * Class providing resolution and higher level facilities for rule objects.
 * </p>
 *
 * @author Kasper Piskorski
 *
 */
public class InferenceRule {

    private final ReasonerQueryImpl body;
    private final ReasonerAtomicQuery head;

    public InferenceRule(Rule rule, GraknGraph graph){
        //TODO simplify once changes propagated to rule objects
        body = new ReasonerQueryImpl(conjunction(rule.getLHS().admin()), graph);
        head = new ReasonerAtomicQuery(conjunction(rule.getRHS().admin()), graph);
    }

    private static Conjunction<VarAdmin> conjunction(PatternAdmin pattern){
        Set<VarAdmin> vars = pattern
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }

    /**
     * @return true if head and body do not share any variables
     */
    public boolean hasDisconnectedHead(){
        return Sets.intersection(body.getVarNames(), head.getVarNames()).isEmpty();
    }

    /**rule needs to be materialised if head atom requires materialisation or if its head contains only fresh variables
     * @return true if the rule needs to be materialised
     */
    public boolean requiresMaterialisation(){
        return getHead().getAtom().requiresMaterialisation()
            || hasDisconnectedHead();}

    /**
     * @return body of the rule of the form head :- body
     */
    public ReasonerQueryImpl getBody(){ return body;}

    /**
     * @return head of the rule of the form head :- body
     */
    public ReasonerAtomicQuery getHead(){ return head;}

    /**
     * @return a conclusion atom which parent contains all atoms in the rule
     */
    public Atom getRuleConclusionAtom() {
        ReasonerQueryImpl ruleQuery = new ReasonerQueryImpl(head);
        Atom atom = ruleQuery.selectAtoms().iterator().next();
        body.getAtoms().forEach(at -> ruleQuery.addAtom(at.copy()));
        return atom;
    }

    private void propagateConstraints(Atom parentAtom){
        //only propagate unambiguous constraints
        Set<VarName> unmappedVars = parentAtom.isRelation() ? ((Relation) parentAtom).getUnmappedRolePlayers() : new HashSet<>();
        Set<Predicate> predicates = parentAtom.getPredicates().stream()
                .filter(pred -> !unmappedVars.contains(pred.getVarName()))
                .collect(toSet());
        Set<Atom> types = parentAtom.getTypeConstraints().stream()
                .filter(type -> !unmappedVars.contains(type.getVarName()))
                .collect(toSet());

        head.addAtomConstraints(predicates);
        body.addAtomConstraints(predicates);
        body.addAtomConstraints(types.stream().filter(type -> !body.containsEquivalentAtom(type)).collect(toSet()));
    }

    private void rewriteHead(Atom parentAtom){
        Atom childAtom = head.getAtom();
        Pair<Atom, Map<VarName, VarName>> rewrite = childAtom.rewriteToUserDefinedWithUnifiers();
        Map<VarName, VarName> rewriteUnifiers = rewrite.getValue();
        Atom newAtom = rewrite.getKey();
        if (newAtom != childAtom){
            head.removeAtom(childAtom);
            head.addAtom(newAtom);
            body.unify(rewriteUnifiers);

            //resolve captures
            Set<VarName> varIntersection = Sets.intersection(body.getVarNames(), parentAtom.getVarNames());
            varIntersection.removeAll(rewriteUnifiers.keySet());
            varIntersection.forEach(var -> body.unify(var, VarName.anon()));
        }
    }

    private void rewriteBody(){
        body.getAtoms().stream()
                .filter(Atomic::isAtom).map(at -> (Atom) at)
                .filter(Atom::isRelation)
                .filter(at -> !at.isUserDefinedName())
                .filter(at -> Objects.nonNull(at.getType()))
                .filter(at -> at.getType().equals(head.getAtom().getType()))
                .forEach(at -> {
                    Atom rewrite = at.rewriteToUserDefined();
                    body.removeAtom(at);
                    body.addAtom(rewrite);
                    });
    }

    private void unify(Map<VarName, VarName> unifiers){
        //do alpha-conversion
        head.unify(unifiers);
        body.unify(unifiers);
    }

    private void unifyViaAtom(Atom parentAtom) {
        Atom childAtom = getRuleConclusionAtom();
        Map<VarName, VarName> unifiers = new HashMap<>();
        if (!parentAtom.getValueVariable().getValue().isEmpty()){
            childAtom.getUnifiers(parentAtom).forEach(unifiers::put);
        }
        //case of match all relation atom
        else{
            Relation extendedParent = ((Relation) AtomicFactory.create(parentAtom, parentAtom.getParentQuery()))
                    .addType(childAtom.getType());
            childAtom.getUnifiers(extendedParent).forEach(unifiers::put);
        }
        unify(unifiers);
    }

    /**
     * make rule consistent variable-wise with the parent atom by means of unification
     * @param parentAtom atom the rule should be unified with
     */
    public void unify(Atom parentAtom) {
        if (parentAtom.isUserDefinedName()) rewriteHead(parentAtom);
        unifyViaAtom(parentAtom);
        if (head.getAtom().isUserDefinedName()) rewriteBody();
        if (parentAtom.isRelation() || parentAtom.isResource()) {
            propagateConstraints(parentAtom);
        }
    }
}
