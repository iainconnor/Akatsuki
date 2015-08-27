package com.sora.util.akatsuki.compiler;

import com.google.common.base.CharMatcher;
import com.google.common.base.MoreObjects;
import com.sora.util.akatsuki.Akatsuki.LoggingLevel;
import com.sora.util.akatsuki.BundleRetainer;
import com.sora.util.akatsuki.Internal;
import com.sora.util.akatsuki.Retained;
import com.sora.util.akatsuki.TransformationTemplate;
import com.sora.util.akatsuki.compiler.BundleContext.SimpleBundleContext;
import com.sora.util.akatsuki.compiler.transformations.CascadingTypeAnalyzer;
import com.sora.util.akatsuki.compiler.transformations.CascadingTypeAnalyzer.Analysis;
import com.sora.util.akatsuki.compiler.transformations.CascadingTypeAnalyzer.InvocationType;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

/**
 * Project: Akatsuki Created by tom91136 on 11/07/2015.
 */
public class BundleRetainerModel {

	private static final Set<Modifier> DISALLOWED_MODIFIERS = EnumSet.of(Modifier.FINAL,
			Modifier.STATIC, Modifier.PRIVATE);

	private final String generatedClassName;
	private final String generatedFqpn;
	private final ProcessorContext context;
	private final TypeElement enclosingClass;
	private BundleRetainerModel superModel;

	public final List<ProcessorElement<?>> fields = new ArrayList<>();

	public static class FqcnModelMap extends HashMap<String, BundleRetainerModel> {

	}

	private BundleRetainerModel(ProcessorContext context, TypeElement enclosingClass) {
		this.context = context;
		this.enclosingClass = enclosingClass;
		this.generatedFqpn = context.elements().getPackageOf(enclosingClass).toString();
		this.generatedClassName = createGeneratedClassName();
	}

	private String createGeneratedClassName() {
		final String enclosingName;
		if (enclosingClass.getNestingKind() != NestingKind.TOP_LEVEL) {
			String simpleClassName = enclosingClass.getQualifiedName().toString()
					.replace(generatedFqpn + ".", "");

			enclosingName = CharMatcher.is('.').replaceFrom(simpleClassName, '$');
		} else {
			enclosingName = enclosingClass.getSimpleName().toString();
		}
		return Internal.generateRetainerClassName(enclosingName);
	}

	public TypeElement enclosingClass() {
		return enclosingClass;
	}

	public String fqcn() {
		return generatedFqpn + "." + generatedClassName;
	}

	// create our model here
	public static FqcnModelMap findRetainedFields(ProcessorContext context,
			RoundEnvironment roundEnv) {
		final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Retained.class);
		FqcnModelMap fqcnMap = new FqcnModelMap();
		int processed = 0;
		boolean verifyOnly = false;
		for (Element element : elements) {

			// skip if error
			if (!annotatedElementValid(context, element)
					|| !enclosingClassValid(context, element)) {
				verifyOnly = true;
				continue;
			}

			// >= 1 error has occurred, we're in verify mode
			if (!verifyOnly) {

				final TypeElement enclosingClass = (TypeElement) element.getEnclosingElement();

				final Retained retained = element.getAnnotation(Retained.class);

				// transient marks the field as skipped
				if (!retained.skip() && !element.getModifiers().contains(Modifier.TRANSIENT)) {
					final BundleRetainerModel model = fqcnMap.computeIfAbsent(
							enclosingClass.getQualifiedName().toString(),
							k -> new BundleRetainerModel(context, enclosingClass));
					final DeclaredType type = context.utils()
							.getClassFromAnnotationMethod(retained::converter);
					model.fields
							.add(new ProcessorElement(retained, (VariableElement) element, type));
					Log.verbose(context, "Element marked", element);
				}
			}
			processed++;
		}

		if (processed != elements.size()) {
			context.messager().printMessage(Kind.NOTE,
					(elements.size() - processed)
							+ " error(s) occurred, no files are generated after the first "
							+ "error has occurred.");
		} else {
			// stage 2, mark any fields that got hidden
			fqcnMap.values().stream().forEach(m -> m.processFieldHiding(fqcnMap));
		}

		return verifyOnly ? null : fqcnMap;
	}

	private static boolean annotatedElementValid(ProcessorContext context, Element element) {
		// sanity check
		if (!element.getKind().equals(ElementKind.FIELD)) {
			context.messager().printMessage(Kind.ERROR, "annotated target must be a field",
					element);
			return false;
		}

		// more sanity check
		if (!(element instanceof VariableElement)) {
			context.messager().printMessage(Kind.ERROR,
					"Element is not a variable?! (should not happen at all) ", element);
			return false;
		}

		// check for invalid modifiers, we can only create classes in the
		// same package
		if (!Collections.disjoint(element.getModifiers(), DISALLOWED_MODIFIERS)) {
			context.messager().printMessage(Kind.ERROR,
					"field with " + DISALLOWED_MODIFIERS.toString() + " cannot be retained",
					element);
			return false;
		}

		return true;
	}

	private static boolean enclosingClassValid(ProcessorContext context, Element element) {
		Element enclosingElement = element.getEnclosingElement();
		while (enclosingElement != null) {
			// skip until we find a class
			if (!enclosingElement.getKind().equals(ElementKind.CLASS))
				break;

			if (!enclosingElement.getKind().equals(ElementKind.CLASS)) {
				context.messager().printMessage(Kind.ERROR,
						"enclosing element(" + enclosingElement.toString() + ") is not a class",
						element);
				return false;
			}

			TypeElement enclosingClass = (TypeElement) enclosingElement;

			// protected, package-private, and public all allow same package
			// access
			if (enclosingClass.getModifiers().contains(Modifier.PRIVATE)) {
				context.messager().printMessage(Kind.ERROR,
						"enclosing class (" + enclosingElement.toString() + ") cannot be private",
						element);
				return false;
			}

			if (enclosingClass.getNestingKind() != NestingKind.TOP_LEVEL
					&& !enclosingClass.getModifiers().contains(Modifier.STATIC)) {
				context.messager().printMessage(Kind.ERROR,
						"enclosing class is nested but not static", element);
				return false;
			}
			enclosingElement = enclosingClass.getEnclosingElement();
		}

		return true;
	}

	public void processFieldHiding(FqcnModelMap map) {
		TypeElement superClass = enclosingClass;
		final TypeMirror objectMirror = context.utils().of(Object.class);
		while ((superClass.getKind() == ElementKind.CLASS && !superClass.equals(objectMirror))) {
			final Element superElement = context.types().asElement(superClass.getSuperclass());
			if (!(superElement instanceof TypeElement)) {
				break;
			}
			superClass = (TypeElement) superElement;
			final BundleRetainerModel model = map.get(superClass.getQualifiedName().toString());
			if (model != null) {
				// we found our closest super class
				if (this.superModel == null)
					this.superModel = model;
				fields.stream().filter(ProcessorElement::notHidden).forEach(field -> {
					final boolean collision = model.fields.stream().anyMatch(
							superField -> superField.fieldName().equals(field.fieldName()));
					field.hidden(collision);
				});
			}
		}
	}

	public void writeSourceToFile(Filer filer, List<TransformationTemplate> templates,
			FqcnModelMap map, List<DeclaredConverterModel> models) throws IOException {

		String sourceName = "source";
		String bundleName = "bundle";

		final PackageElement enclosingClassPackage = context.elements()
				.getPackageOf(enclosingClass);

		final SimpleBundleContext bundleContext = new SimpleBundleContext(sourceName, bundleName);

		final ClassName sourceClassName = ClassName.get(enclosingClass);

		TypeVariableName actualClassCapture = TypeVariableName.get("T", sourceClassName);

		final ParameterSpec sourceSpec = ParameterSpec
				.builder(actualClassCapture, sourceName, Modifier.FINAL).build();

		final ParameterSpec bundleSpec = ParameterSpec
				.builder(ClassName.get(AndroidTypes.Bundle.asMirror(context)), bundleName,
						Modifier.FINAL)
				.build();

		final Builder saveMethodBuilder = MethodSpec.methodBuilder("save")
				.addModifiers(Modifier.PUBLIC).returns(void.class).addParameter(sourceSpec)
				.addParameter(bundleSpec);

		final Builder restoreMethodBuilder = MethodSpec.methodBuilder("restore")
				.addModifiers(Modifier.PUBLIC).returns(void.class).addParameter(sourceSpec)
				.addParameter(bundleSpec);

		if (superModel != null) {
			String superInvocation = "super.$L($L, $L)";
			saveMethodBuilder.addStatement(superInvocation, "save", sourceName, bundleName);
			restoreMethodBuilder.addStatement(superInvocation, "restore", sourceName, bundleName);
		}

		final TypeResolvingContext resolvingContext = new TypeResolvingContext(templates, models,
				map, context);

		for (ProcessorElement<?> field : fields) {

			final CascadingTypeAnalyzer<?, ?, ?> strategy = resolvingContext
					.findTransformationStrategy(field);
			if (strategy == null) {
				context.messager().printMessage(Kind.ERROR,
						"unsupported field, reflected type is " + field.refinedMirror()
								+ " representing class is " + field.refinedMirror().getClass(),
						field.originatingElement());
			} else {

				try {
					final Analysis save = strategy.transform(bundleContext, field,
							InvocationType.SAVE);
					final Analysis restore = strategy.transform(bundleContext, field,
							InvocationType.RESTORE);

					saveMethodBuilder.addCode(JavaPoetUtils.escapeStatement(
							save.preEmitOnce() + save.emit() + save.postEmitOnce()));
					restoreMethodBuilder.addCode(JavaPoetUtils.escapeStatement(
							restore.preEmitOnce() + restore.emit() + restore.postEmitOnce()));
				} catch (Exception | Error e) {
					context.messager().printMessage(Kind.ERROR, "an exception occurred: " + e,
							field.originatingElement());
					throw new RuntimeException(e);
				}
			}
		}

		// generate class name here

		TypeSpec.Builder typeSpecBuilder = TypeSpec.classBuilder(generatedClassName)
				.addModifiers(Modifier.PUBLIC).addMethod(saveMethodBuilder.build())
				.addMethod(restoreMethodBuilder.build()).addTypeVariable(actualClassCapture);

		if (superModel != null) {

			final ClassName className = ClassName.get(superModel.generatedFqpn,
					superModel.generatedClassName);

			typeSpecBuilder
					.superclass(ParameterizedTypeName.get(className, TypeVariableName.get("T")));
		} else {
			final ParameterizedTypeName interfaceName = ParameterizedTypeName
					.get(ClassName.get(BundleRetainer.class), TypeVariableName.get("T"));
			typeSpecBuilder.addSuperinterface(interfaceName);
		}

		JavaFile javaFile = JavaFile.builder(generatedFqpn, typeSpecBuilder.build()).build();

		javaFile.writeTo(filer);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this).add("generatedClassName", generatedClassName)
				.add("generatedFqpn", generatedFqpn).add("context", context)
				.add("enclosingClass", enclosingClass).add("superModel", superModel)
				.add("fields", fields).toString();
	}

}
