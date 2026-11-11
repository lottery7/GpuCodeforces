package dev.gpucodeforces.backend.processor

import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import java.math.BigDecimal
import dev.gpucodeforces.backend.api.model.Language as LanguageApiModel
import dev.gpucodeforces.backend.api.model.Processor as ProcessorApiModel
import dev.gpucodeforces.backend.api.model.Score as ScoreApiModel
import dev.gpucodeforces.backend.api.model.ScoreType as ScoreTypeApiModel

fun ScoreType.toApiModel(): ScoreTypeApiModel = ScoreTypeApiModel.fromValue(name)

/** A score as stored: the decimal is kept as it is. */
fun scoreApiModel(value: BigDecimal, type: ScoreType): ScoreApiModel = ScoreApiModel(value, type.toApiModel())

fun Score.toApiModel(): ScoreApiModel = scoreApiModel(BigDecimal.valueOf(value), type)

fun ProcessorInfo.toApiModel(model: String): ProcessorApiModel = ProcessorApiModel(
    id,
    title,
    model,
    languages.map { LanguageApiModel(it.id, it.title, it.extension) },
    scoreType.toApiModel(),
    emulated,
)
