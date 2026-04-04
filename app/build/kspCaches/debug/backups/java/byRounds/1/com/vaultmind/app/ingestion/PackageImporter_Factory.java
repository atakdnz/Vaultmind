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
public final class PackageImporter_Factory implements Factory<PackageImporter> {
  private final Provider<Context> contextProvider;

  private final Provider<VaultRepository> vaultRepositoryProvider;

  public PackageImporter_Factory(Provider<Context> contextProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.vaultRepositoryProvider = vaultRepositoryProvider;
  }

  @Override
  public PackageImporter get() {
    return newInstance(contextProvider.get(), vaultRepositoryProvider.get());
  }

  public static PackageImporter_Factory create(Provider<Context> contextProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    return new PackageImporter_Factory(contextProvider, vaultRepositoryProvider);
  }

  public static PackageImporter newInstance(Context context, VaultRepository vaultRepository) {
    return new PackageImporter(context, vaultRepository);
  }
}
