package com.lagradost.clouddream.ui

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Glue that connects the navigation graph destination to [CloudDreamCloudScreen]. */
class CloudDreamCloudSettingsFragment : BaseComposeFragment(),
    SearchableSettings by CloudDreamCloudScreen
