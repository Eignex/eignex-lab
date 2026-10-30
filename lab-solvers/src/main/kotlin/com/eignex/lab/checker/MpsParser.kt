package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

/** The retained MPS source checker shares its parsed model with independent reference witness checks. */
internal typealias MpsModel = com.eignex.klause.formats.mps.MpsModel
internal typealias MpsConstraint = com.eignex.klause.formats.mps.MpsConstraint
internal typealias ObjectiveSense = com.eignex.klause.ir.ObjectiveSense
internal typealias Mps = com.eignex.klause.formats.mps.Mps
