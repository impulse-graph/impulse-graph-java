package org.impulsegraph.api.spi;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry coordinating discovery and access to the active
 * {@link ImpulseEngineProvider}.
 */
public final class ImpulseEngineRegistry {

	private static final AtomicReference<ImpulseEngineProvider> REGISTERED_PROVIDER = new AtomicReference<>();

	private ImpulseEngineRegistry() {
	}

	/**
	 * Explicitly registers an engine provider.
	 *
	 * @param provider
	 *            the engine provider to set
	 */
	public static void register(ImpulseEngineProvider provider) {
		REGISTERED_PROVIDER.set(provider);
	}

	/**
	 * Retrieves the active engine provider, resolving via ServiceLoader or fallback
	 * if uninitialized.
	 *
	 * @return the active engine provider
	 */
	public static ImpulseEngineProvider getProvider() {
		ImpulseEngineProvider p = REGISTERED_PROVIDER.get();
		if (p != null) {
			return p;
		}

		// 1. Discover via ServiceLoader
		ServiceLoader<ImpulseEngineProvider> loader = ServiceLoader.load(ImpulseEngineProvider.class);
		Optional<ImpulseEngineProvider> first = loader.findFirst();
		if (first.isPresent()) {
			REGISTERED_PROVIDER.compareAndSet(null, first.get());
			return REGISTERED_PROVIDER.get();
		}

		// 2. Fallback to default VM provider via reflection
		try {
			Class<?> defaultCls = Class.forName("org.impulsegraph.vm.provider.DefaultImpulseEngineProvider");
			ImpulseEngineProvider provider = (ImpulseEngineProvider) defaultCls.getDeclaredConstructor().newInstance();
			REGISTERED_PROVIDER.compareAndSet(null, provider);
			return REGISTERED_PROVIDER.get();
		} catch (Throwable ignored) {
		}

		throw new IllegalStateException("No ImpulseEngineProvider registered or discovered on classpath.");
	}
}
