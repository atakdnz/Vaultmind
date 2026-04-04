package com.vaultmind.app.rag;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
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
public final class PromptBuilder_Factory implements Factory<PromptBuilder> {
  @Override
  public PromptBuilder get() {
    return newInstance();
  }

  public static PromptBuilder_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static PromptBuilder newInstance() {
    return new PromptBuilder();
  }

  private static final class InstanceHolder {
    private static final PromptBuilder_Factory INSTANCE = new PromptBuilder_Factory();
  }
}
