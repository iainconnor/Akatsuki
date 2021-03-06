package com.sora.util.akatsuki;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

import com.sora.util.akatsuki.AkatsukiConfig.Flags;
import com.sora.util.akatsuki.AkatsukiConfig.LoggingLevel;
import com.sora.util.akatsuki.AkatsukiConfig.OptFlags;
import com.sora.util.akatsuki.models.FieldModel;

public class Configuration {

	private final AkatsukiConfig config;

	private EnumSet<OptFlags> optFlags;
	private EnumSet<Flags> flags;

	public Configuration(AkatsukiConfig config) {
		this.config = config;
	}

	public void validate(ProcessorContext context) {

		if (config == null) {
			throw new NullPointerException("config == null");
		}

		optFlags = of(OptFlags.class, config.optFlags());
		flags = of(Flags.class, config.flags());

		if (optFlags.size() != config.optFlags().length) {
			Log.warn(context,
					"OptFlags has duplicate items: " + Arrays.toString(config.optFlags()));
		}

		if (flags.size() != config.flags().length) {
			Log.warn(context, "Flags has duplicate items : " + Arrays.toString(config.flags()));
		}

		if (optFlags.contains(OptFlags.VECTORIZE_INHERITANCE)
				&& !optFlags.contains(OptFlags.CLASS_LUT)) {
			Log.warn(context, "OptFlags.VECTORIZE_INHERITANCE is dependent on OptFlags.CLASS_LUT");
		}

	}

	public LoggingLevel loggingLevel() {
		return config.loggingLevel();
	}

	public boolean allowVolatile() {
		return config.allowVolatile();
	}

	public boolean allowTransient() {
		return config.allowTransient();
	}

	public boolean fieldAllowed(Element element) {
		Set<Modifier> modifiers = element.getModifiers();

		if (!allowTransient() && modifiers.contains(Modifier.TRANSIENT))
			return false;

		if (!allowVolatile() && modifiers.contains(Modifier.VOLATILE))
			return false;

		return true;
	}

	public boolean fieldAllowed(FieldModel model) {
		return fieldAllowed(model.element);
	}

	public EnumSet<OptFlags> optFlags() {
		return optFlags;
	}

	public EnumSet<Flags> flags() {
		return flags;
	}

	/**
	 * Returns the global default for {@link ArgConfig}
	 * 
	 * @return a {@link ArgConfig} instance, never null
	 */
	public ArgConfig argConfig() {
		return config.argConfig();
	}

	/**
	 * Returns the global default for {@link RetainConfig}
	 * 
	 * @return a {@link RetainConfig} instance, never null
	 */
	public RetainConfig retainConfig() {
		return config.retainConfig();
	}

	private static <E extends Enum<E>> EnumSet<E> of(Class<E> type, E[] array) {
		EnumSet<E> result = EnumSet.noneOf(type);
		Collections.addAll(result, array);
		return result;
	}

	@Override
	public String toString() {
		return "Configuration{" + "config=" + config + ", optFlags=" + optFlags + ", flags=" + flags
				+ '}';
	}
}
