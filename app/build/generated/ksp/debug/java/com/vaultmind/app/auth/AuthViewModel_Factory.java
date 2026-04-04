package com.vaultmind.app.auth;

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
public final class AuthViewModel_Factory implements Factory<AuthViewModel> {
  private final Provider<AuthManager> authManagerProvider;

  private final Provider<KeystoreManager> keystoreManagerProvider;

  private final Provider<VaultRepository> vaultRepositoryProvider;

  public AuthViewModel_Factory(Provider<AuthManager> authManagerProvider,
      Provider<KeystoreManager> keystoreManagerProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    this.authManagerProvider = authManagerProvider;
    this.keystoreManagerProvider = keystoreManagerProvider;
    this.vaultRepositoryProvider = vaultRepositoryProvider;
  }

  @Override
  public AuthViewModel get() {
    return newInstance(authManagerProvider.get(), keystoreManagerProvider.get(), vaultRepositoryProvider.get());
  }

  public static AuthViewModel_Factory create(Provider<AuthManager> authManagerProvider,
      Provider<KeystoreManager> keystoreManagerProvider,
      Provider<VaultRepository> vaultRepositoryProvider) {
    return new AuthViewModel_Factory(authManagerProvider, keystoreManagerProvider, vaultRepositoryProvider);
  }

  public static AuthViewModel newInstance(AuthManager authManager, KeystoreManager keystoreManager,
      VaultRepository vaultRepository) {
    return new AuthViewModel(authManager, keystoreManager, vaultRepository);
  }
}
