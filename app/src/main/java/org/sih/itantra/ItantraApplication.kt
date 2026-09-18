package org.sih.itantra

import android.app.Application
import org.sih.itantra.domain.repository.CommunicationRepository

class ItantraApplication : Application() {
    lateinit var communicationRepository: CommunicationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        communicationRepository = CommunicationRepository(this)
    }
}
