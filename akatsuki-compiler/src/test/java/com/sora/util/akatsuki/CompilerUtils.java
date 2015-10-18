package com.sora.util.akatsuki;

import static com.google.common.base.Charsets.UTF_8;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.sora.util.akatsuki.InMemoryJavaFileManager.InMemoryJavaFileObject;

public class CompilerUtils {

	static Result compile(ClassLoader loader, Iterable<Processor> processors,
			Iterable<String> options, JavaFileObject... objects) {
		// we need all this because we got a annotation processor, the generated
		// class has to go into memory too
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
		InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(
				compiler.getStandardFileManager(diagnosticCollector, Locale.getDefault(), UTF_8),
				loader);
		CompilationTask task = compiler.getTask(null, fileManager, diagnosticCollector, options,
				null, Arrays.asList(objects));
		task.setProcessors(processors);

		Exception exception = null;
		try {
			if (!task.call())
				throw new RuntimeException("File compiled with error, the cause is unknown");
		} catch (Exception e) {
			exception = e;
		}

		System.out.println(printVertically(diagnosticCollector.getDiagnostics()));

		return new Result(fileManager.getClassLoader(StandardLocation.CLASS_OUTPUT),
				fileManager.getOutputFiles(),
				exception == null ? null
						: new RuntimeException(
								"Compilation failed:\n"
										+ printVertically(diagnosticCollector.getDiagnostics()),
								exception));
	}

	static String printVertically(List<?> collection) {
		return Joiner.on("\n\t>").join(new ArrayList<>(collection));
	}

	static String printAllSources(List<JavaFileObject> sources, String linePrefix) {
		final List<InMemoryJavaFileObject> sourceFiles = sources.stream()
				.filter(f -> f instanceof InMemoryJavaFileObject)
				.map(f -> (InMemoryJavaFileObject) f).filter(InMemoryJavaFileObject::isSource)
				.collect(Collectors.toList());
		final StringWriter writer = new StringWriter();

		writer.append("\n").append(linePrefix).append("Overview:");
		for (JavaFileObject fileObject : sources) {
			writer.append("\n").append(linePrefix).append("\t")
					.append(fileObject.toUri().toString());
		}

		writer.append("\n").append(linePrefix).append("Generated source(s):\n");
		for (InMemoryJavaFileObject file : sourceFiles) {
			writer.append(linePrefix).append("File: ").append(file.toUri().toString()).append("\n");
			try {
				file.printSource(writer, linePrefix+"\t");
			} catch (IOException e) {
				// what else can we do?
				writer.append(linePrefix)
						.append("An exception occurred while trying to print the " + "source:\n");
				e.printStackTrace(new PrintWriter(writer));
			}
			writer.append("\n").append(linePrefix).append("===============================\n");
		}

		return writer.toString();
	}

	static class Result {

		public final ClassLoader classLoader;
		public final ImmutableList<JavaFileObject> sources;
		public final Exception compilationException;

		public Result(ClassLoader classLoader, ImmutableList<JavaFileObject> sources,
				Exception compilationException) {
			this.classLoader = classLoader;
			this.sources = sources;
			this.compilationException = compilationException;
		}

		public String printGeneratedSources(String linePrefix) {
			return CompilerUtils.printAllSources(sources, linePrefix);
		}
	}

}