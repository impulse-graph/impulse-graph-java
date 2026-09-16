package org.impulsegraph.api.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.impulsegraph.compiler.ast.ImpScmNode;

@AnalyzeClasses(packages = "org.impulsegraph", importOptions = {ImportOption.DoNotIncludeTests.class})
public class ArchitectureRulesTest {

	/**
	 * Enforces AGENTS.md §3.2: impulse-core and impulse-api MUST maintain zero
	 * third-party runtime dependencies. Production classes in org.impulsegraph..
	 * may only depend on JDK standard libraries (java.., jdk..) and other
	 * org.impulsegraph packages.
	 */
	@ArchTest
	public static final ArchRule noThirdPartyRuntimeDependencies = classes().that()
			.resideInAPackage("org.impulsegraph..").should().onlyDependOnClassesThat()
			.resideInAnyPackage("java..", "jdk..", "org.impulsegraph..", "");

	/**
	 * Enforces clean API layer isolation: classes in org.impulsegraph.api.. must
	 * not depend directly on concrete storage or execution engine packages
	 * (org.impulsegraph.storage.. or org.impulsegraph.vm..).
	 */
	@ArchTest
	public static final ArchRule apiLayerIsolationFromStorageAndVm = noClasses().that()
			.resideInAPackage("org.impulsegraph.api..").should().dependOnClassesThat()
			.resideInAnyPackage("org.impulsegraph.storage..", "org.impulsegraph.vm..");

	/**
	 * Enforces compiler AST isolation: AST intermediate representation nodes must
	 * not depend on schema code generation packages.
	 */
	@ArchTest
	public static final ArchRule astShouldNotDependOnSchema = noClasses().that()
			.resideInAPackage("org.impulsegraph.compiler.ast..").should().dependOnClassesThat()
			.resideInAPackage("org.impulsegraph.api.schema..");

	/**
	 * All Scm* AST node implementations in org.impulsegraph.compiler.ast must
	 * implement the root ImpScmNode interface.
	 */
	@ArchTest
	public static final ArchRule allScmNodesImplementImpScmNode = classes().that()
			.resideInAPackage("org.impulsegraph.compiler.ast").and().haveSimpleNameStartingWith("Scm").should()
			.beAssignableTo(ImpScmNode.class);
}
