package jo.codeide.tooling.client

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.GradleToolingRepository
import javax.inject.Singleton

/**
 * Câblage du client tooling (G3) : l'implémentation de référence du port
 * du domaine — les features n'injectent JAMAIS [GradleApiImpl] directement
 * (règle §2.2 du prompt Tooling : consommation par l'interface du domaine,
 * comme [jo.codeide.core.domain.TerminalSessionRepository]).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ModuleToolingClient {
    /** Façade publique du tooling derrière le port du domaine. */
    @Binds
    @Singleton
    abstract fun lierRepository(implementation: GradleApiImpl): GradleToolingRepository
}
