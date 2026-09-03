package org.impulsegraph.vm;

public class ShaderAbortException extends RuntimeException {
	public static final ShaderAbortException INSTANCE = new ShaderAbortException();

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
