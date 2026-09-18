package dev.aoframe.render

/**
 * Only special-cases landscape photos (letterbox + blurred backdrop) on
 * a portrait panel; any portrait photo is assumed to already be a
 * reasonable fit. A strict aspect-ratio tolerance (letterbox any photo
 * whose ratio doesn't closely match the panel's, portrait included) was
 * tried first and over-triggered in practice: a 3:4/4:5 phone photo
 * crop-to-fill still reads fine on this kind of panel, letterboxing it
 * looks worse, not better. Settled on orientation match instead, computed
 * generically rather than assuming the panel is always portrait.
 */
object PhotoFit {
    fun shouldCover(assetWidth: Int, assetHeight: Int, panelWidth: Int, panelHeight: Int): Boolean {
        if (assetWidth <= 0 || assetHeight <= 0 || panelWidth <= 0 || panelHeight <= 0) return true
        val assetIsPortrait = assetHeight >= assetWidth
        val panelIsPortrait = panelHeight >= panelWidth
        return assetIsPortrait == panelIsPortrait
    }
}
