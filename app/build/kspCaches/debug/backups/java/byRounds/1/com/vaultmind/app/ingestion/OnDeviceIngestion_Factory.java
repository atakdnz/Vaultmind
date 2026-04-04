package com.vaultmind.app.ingestion;

import android.content.Context;
import com.vaultmind.app.vault.VaultRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class OnDeviceIngestion_Factory implements Factory<OnDeviceIngestion> {
  private final Provider<Context> contextProvider;

  private final Provider<TextChunker> chunkerProvider;

  private final Provider<EmbeddingEngine> embeddingEngineProvider;

  private final Provider<VaultRepository> vaultRepositoryProvider;

  public OnDeviceIngestion_Factory(Provider<Context> contextProvider,
      Provider<TextChunker> chunkerProvider, Provider<EmbeddingEngine> embeddingEngineProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.chunkerProvider = chunkerProvider;
    this.embeddingEngineProvider = embeddingEngineProvider;
    this.vaultRepositoryProvider = vaultRepositoryProvider;
  }

  @Override
  public OnDeviceIngestion get() {
    return newInstance(contextProvider.get(), chunkerProvider.get(), embeddingEngineProvider.get(), vaultRepositoryProvider.get());
  }

  public static OnDeviceIngestion_Factory create(Provider<Context> contextProvider,
      Provider<TextChunker> chunkerProvider, Provider<EmbeddingEngine> embeddingEngineProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    return new OnDeviceIngestion_Factory(contextProvider, chunkerProvider, embeddingEngineProvider, vaultRepositoryProvider);
  }

  public static OnDeviceIngestion newInstance(Context context, TextChunker chunker,
      EmbeddingEngine embeddingEngine, VaultRepository vaultRepository) {
    return new OnDeviceIngestion(context, chunker, embeddingEngine, vaultRepository);
  }
}
