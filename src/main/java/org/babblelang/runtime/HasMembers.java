package org.babblelang.runtime;

/**
 * A value with a fixed set of members : an instance of a Babble class, or a class or
 * interface value with static members.
 */
public interface HasMembers {
    /**
     * @param own true for {@code this.name}, the only selection that may reach a private
     *            member
     */
    Object getMember(String name, boolean own);

    void setMember(String name, Object value, boolean own);
}
