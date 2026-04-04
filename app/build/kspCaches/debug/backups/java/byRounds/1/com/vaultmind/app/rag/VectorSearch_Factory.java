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
public final class VectorSearch_Factory implements Factory<VectorSearch> {
  @Override
  public VectorSearch get() {
    return newInstance();
  }

  public static VectorSearch_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static VectorSearch newInstance() {
    return new VectorSearch();
  }

  private static final class InstanceHolder {
    private static final VectorSearch_Factory INSTANCE = new VectorSearch_Factory();
  }
}
