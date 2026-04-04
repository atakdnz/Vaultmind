package com.vaultmind.app.vault;

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
public final class VaultListViewModel_Factory implements Factory<VaultListViewModel> {
  private final Provider<VaultRepository> vaultRepositoryProvider;

  public VaultListViewModel_Factory(Provider<VaultRepository> vaultRepositoryProvider) {
    this.vaultRepositoryProvider = vaultRepositoryProvider;
  }

  @Override
  public VaultListViewModel get() {
    return newInstance(vaultRepositoryProvider.get());
  }

  public static VaultListViewModel_Factory create(
      Provider<VaultRepository> vaultRepositoryProvider) {
    return new VaultListViewModel_Factory(vaultRepositoryProvider);
  }

  public static VaultListViewModel newInstance(VaultRepository vaultRepository) {
    return new VaultListViewModel(vaultRepository);
  }
}
