package com.vaultmind.app.di;

import com.vaultmind.app.ingestion.TextChunker;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class AppModule_ProvideTextChunkerFactory implements Factory<TextChunker> {
  @Override
  public TextChunker get() {
    return provideTextChunker();
  }

  public static AppModule_ProvideTextChunkerFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static TextChunker provideTextChunker() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideTextChunker());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideTextChunkerFactory INSTANCE = new AppModule_ProvideTextChunkerFactory();
  }
}
