package com.vaultmind.app.rag;

import com.vaultmind.app.ingestion.EmbeddingEngine;
import com.vaultmind.app.settings.AppPreferences;
import com.vaultmind.app.vault.VaultRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class ChatViewModel_Factory implements Factory<ChatViewModel> {
  private final Provider<LlmEngine> llmEngineProvider;

  private final Provider<EmbeddingEngine> embeddingEngineProvider;

  private final Provider<VectorSearch> vectorSearchProvider;

  private final Provider<PromptBuilder> promptBuilderProvider;

  private final Provider<VaultRepository> vaultRepositoryProvider;

  private final Provider<AppPreferences> appPreferencesProvider;

  public ChatViewModel_Factory(Provider<LlmEngine> llmEngineProvider,
      Provider<EmbeddingEngine> embeddingEngineProvider,
      Provider<VectorSearch> vectorSearchProvider, Provider<PromptBuilder> promptBuilderProvider,
      Provider<VaultRepository> vaultRepositoryProvider,
      Provider<AppPreferences> appPreferencesProvider) {
    this.llmEngineProvider = llmEngineProvider;
    this.embeddingEngineProvider = embeddingEngineProvider;
    this.vectorSearchProvider = vectorSearchProvider;
    this.promptBuilderProvider = promptBuilderProvider;
    this.vaultRepositoryProvider = vaultRepositoryProvider;
    this.appPreferencesProvider = appPreferencesProvider;
  }

  @Override
  public ChatViewModel get() {
    return newInstance(llmEngineProvider.get(), embeddingEngineProvider.get(), vectorSearchProvider.get(), promptBuilderProvider.get(), vaultRepositoryProvider.get(), appPreferencesProvider.get());
  }

  public static ChatViewModel_Factory create(Provider<LlmEngine> llmEngineProvider,
      Provider<EmbeddingEngine> embeddingEngineProvider,
      Provider<VectorSearch> vectorSearchProvider, Provider<PromptBuilder> promptBuilderProvider,
      Provider<VaultRepository> vaultRepositoryProvider,
      Provider<AppPreferences> appPreferencesProvider) {
    return new ChatViewModel_Factory(llmEngineProvider, embeddingEngineProvider, vectorSearchProvider, promptBuilderProvider, vaultRepositoryProvider, appPreferencesProvider);
  }

  public static ChatViewModel newInstance(LlmEngine llmEngine, EmbeddingEngine embeddingEngine,
      VectorSearch vectorSearch, PromptBuilder promptBuilder, VaultRepository vaultRepository,
      AppPreferences appPreferences) {
    return new ChatViewModel(llmEngine, embeddingEngine, vectorSearch, promptBuilder, vaultRepository, appPreferences);
  }
}
