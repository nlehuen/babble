package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;
import org.junit.Assert;
import org.junit.Test;

public class NamespaceTest {

    @Test
    public void testRootScopeConstruction() {
        Namespace root = new Namespace();
        // Parent being null is implicitly tested by testLeave_OnRoot throwing IllegalStateException
        Assert.assertTrue("Root namespace locals should be empty initially", root.locals.isEmpty());
    }

    @Test
    public void testDefineAndGet_Success() {
        Namespace ns = new Namespace();
        Slot<Object> slot = ns.define("x", false);
        Assert.assertNotNull(slot);
        Assert.assertEquals("x", slot.getName());
        Assert.assertFalse(slot.isFinal());
        Assert.assertFalse("Slot should initially be in 'unset' state", slot.isSet()); // Check isSet()

        // Attempting to get from an unset slot should throw IllegalStateException
        try {
            slot.get();
            Assert.fail("Getting value from an unset slot should throw IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertEquals("Slot not set : x", e.getMessage());
        }

        slot.set(100);
        Assert.assertTrue("Slot should be 'set' after setting a value", slot.isSet());
        Assert.assertEquals(100, slot.get());

        Slot<Object> retrievedSlot = ns.get("x");
        Assert.assertSame(slot, retrievedSlot);
        Assert.assertEquals(100, retrievedSlot.get());
    }

    @Test(expected = BabbleException.class)
    public void testDefine_AlreadyDefined() {
        Namespace ns = new Namespace();
        ns.define("x", false);
        ns.define("x", false); // Should throw
    }

    @Test
    public void testIsDeclared_Local() {
        Namespace ns = new Namespace();
        ns.define("x", false);
        Assert.assertTrue("isDeclared should be true for defined local variable", ns.isDeclared("x"));
        Assert.assertFalse("isDeclared should be false for undefined variable", ns.isDeclared("y"));
    }

    @Test(expected = BabbleException.class)
    public void testGet_NotDefined() {
        Namespace ns = new Namespace();
        ns.get("y"); // Should throw
    }

    @Test
    public void testEnterAnonymousAndLeave() {
        Namespace parent = new Namespace();
        Namespace child = parent.enter(null);
        Assert.assertNotNull(child);
        Assert.assertNotSame(parent, child);
        Assert.assertSame("Child's parent should be the original namespace", parent, child.leave());
    }

    @Test(expected = IllegalStateException.class)
    public void testLeave_OnRoot() {
        Namespace root = new Namespace();
        root.leave(); // Should throw
    }

    // --- Tests for parent scope interaction ---

    @Test
    public void testGet_FromParent() {
        Namespace parent = new Namespace();
        parent.define("x", false).set("parentValue");

        Namespace child = parent.enter(null);
        Slot<Object> slot = child.get("x"); // Should get from parent
        Assert.assertNotNull(slot);
        Assert.assertEquals("parentValue", slot.get());
        Assert.assertSame(parent.get("x"), slot); // Should be the same slot instance
    }

    @Test
    public void testIsDeclared_InParent() {
        Namespace parent = new Namespace();
        parent.define("x", false);

        Namespace child = parent.enter(null);
        Assert.assertTrue("isDeclared should find variable in parent", child.isDeclared("x"));
        Assert.assertFalse("isDeclared should be false for var not in child or parent", child.isDeclared("y"));
    }

    @Test
    public void testDefine_ShadowsParent() {
        Namespace parent = new Namespace();
        parent.define("x", false).set("parentValue");

        Namespace child = parent.enter(null);
        child.define("x", false).set("childValue"); // Shadowing

        Assert.assertEquals("childValue", child.get("x").get());
        Assert.assertEquals("parentValue", parent.get("x").get()); // Parent unchanged
        Assert.assertNotSame(parent.get("x"), child.get("x"));
    }

    @Test(expected = BabbleException.class)
    public void testGet_NotDefinedInChain() {
        Namespace grandparent = new Namespace();
        Namespace parent = grandparent.enter(null);
        Namespace child = parent.enter(null);
        child.get("nonExistent"); // Should throw
    }

    // --- Tests for enter(String name) ---
    @Test
    public void testEnterNamed_NewChild() {
        Namespace parent = new Namespace();
        Namespace child = parent.enter("myScope");
        Assert.assertNotNull(child);
        Assert.assertNotSame(parent, child);
        Assert.assertSame(parent, child.leave());

        Slot<Object> scopeSlot = parent.get("myScope");
        Assert.assertNotNull(scopeSlot);
        Assert.assertTrue(scopeSlot.isFinal());
        Assert.assertSame(child, scopeSlot.get());
    }

    @Test
    public void testEnterNamed_ReEnterExistingChild() {
        Namespace parent = new Namespace();
        Namespace child1 = parent.enter("myScope");
        Namespace child2 = parent.enter("myScope"); // Re-enter

        Assert.assertSame("Re-entering a named scope should return the same instance", child1, child2);
    }

    @Test(expected = ClassCastException.class)
    public void testEnterNamed_NameExistsAsNonNamespace() {
        Namespace parent = new Namespace();
        parent.define("myScope", false).set("not a namespace");
        parent.enter("myScope"); // Should throw ClassCastException
    }
}
