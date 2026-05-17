/**
 * TEMPORARY debug tooling: edit {@code Card.json} from the collection album while debug mode is on.
 * <p>
 * To remove this feature entirely:
 * <ol>
 *   <li>Delete the {@code com.osrstcg.debug.catalogedit} package.</li>
 *   <li>Search the codebase for {@code DEBUG_CARD_EDIT} and remove every marked hook.</li>
 *   <li>Remove {@link com.osrstcg.data.CardDatabase#forceReloadForDebug} if nothing else uses it.</li>
 * </ol>
 */
package com.osrstcg.debug.catalogedit;
