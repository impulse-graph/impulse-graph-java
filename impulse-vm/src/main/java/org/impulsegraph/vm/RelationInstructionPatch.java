package org.impulsegraph.vm;

/**
 * Patch entry for dynamic physical relation binding during blue/green snapshot swaps.
 *
 * @param pc Program counter offset of instruction to patch
 * @param logicalRelationName Logical relation catalog name
 * @param srcReg Source register ID
 * @param dstReg Destination register ID
 */
public record RelationInstructionPatch(long pc, String logicalRelationName, short srcReg, short dstReg) {}
