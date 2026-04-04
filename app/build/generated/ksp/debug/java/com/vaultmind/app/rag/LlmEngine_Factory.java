package com.vaultmind.app.rag;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class LlmEngine_Factory implements Factory<LlmEngine> {
  private final Provider<Context> contextProvider;

  public LlmEngine_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public LlmEngine get() {
    return newInstance(contextProvider.get());
  }

  public static LlmEngine_Factory create(Provider<Context> contextProvider) {
    return new LlmEngine_Factory(contextProvider);
  }

  public static LlmEngine newInstance(Context context) {
    return new LlmEngine(context);
  }
}
