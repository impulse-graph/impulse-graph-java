package org.impulsegraph.vm.provider;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.spi.ImpulseEngineProvider;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.vm.statement.ImpulseStatementImpl;
import org.impulsegraph.vm.traversal.DefaultDomainView;

/**
 * Default VM execution engine provider implementation registered via
 * ServiceLoader.
 */
public class DefaultImpulseEngineProvider implements ImpulseEngineProvider {

	@Override
	public DomainView createDomainView(ImpulseGraphSnapshot snapshot, String domainName, int domainId, long nodeCount) {
		return DefaultDomainView.getOrCreate(snapshot, domainName, domainId, nodeCount);
	}

	@Override
	public ImpulseStatement createStatement(ImpulseGraphSnapshot snapshot, String query) {
		return new ImpulseStatementImpl(snapshot, query);
	}
}
