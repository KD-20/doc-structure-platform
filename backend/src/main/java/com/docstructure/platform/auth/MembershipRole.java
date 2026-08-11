package com.docstructure.platform.auth;

/** Ordered least to most privileged; RoleHierarchyConfig grants each role everything below it. */
public enum MembershipRole {
    VIEWER,
    EDITOR,
    ADMIN,
    OWNER
}
