package com.sora.util.akatsuki.compiler;

import javax.lang.model.type.TypeMirror;

import com.sora.util.akatsuki.compiler.analyzers.CascadingTypeAnalyzer;
import com.sora.util.akatsuki.compiler.analyzers.CascadingTypeAnalyzer.Analysis;
import com.sora.util.akatsuki.compiler.analyzers.Element;

public class TransformationContext extends ProcessorContext {

	private final TypeAnalyzerResolver resolver;

	public TransformationContext(ProcessorContext context, TypeAnalyzerResolver resolver) {
		super(context);
		this.resolver = resolver;
	}

	public TransformationContext(TransformationContext context) {
		this(context, context.resolver);
	}

	protected CascadingTypeAnalyzer<?, ? extends TypeMirror, ? extends Analysis> resolve(Element<?> element) {
		return resolver.resolve(element);
	}
}
