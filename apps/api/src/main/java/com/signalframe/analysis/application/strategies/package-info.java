/**
 * Domain strategy dictionaries for the analysis protocol (TASK-05).
 *
 * <p>Each strategy is an immutable, pure recommendation set for exactly one {@link
 * com.signalframe.contract.DomainType}: candidate variables, candidate mechanisms, verification
 * metrics, domain hazards and a bounded guidance paragraph. A strategy never states a conclusion,
 * never assigns confidence and never changes the meaning of FACT, INFERENCE, HYPOTHESIS, PREDICTION
 * or UNKNOWN (EP-12).
 *
 * <p>Design notes for reviewers:
 *
 * <ul>
 *   <li>The frozen interface exposes {@code spec()} without an argument, and a spec carries one
 *       {@code DomainType}, so one dictionary requires one strategy class per domain. BUSINESS,
 *       FINANCE and POLICY are the Wave 2A goal; the remaining dictionaries of
 *       DOMAIN_STRATEGY_CONTRACT.md §7 land here in the same change and are not mapped to the
 *       fallback with a documented gap.
 *   <li>Specialised dictionaries declare specificity 2; {@code DefaultDomainStrategy} is the only
 *       specificity 0 strategy and the only one that claims every domain, so OTHER and any future
 *       domain always resolve (DS-02, DS-03, DS-05).
 *   <li>Mechanism templates can only be constructed at {@code SPECULATIVE} (DS-08); raising a level
 *       is the analysis stage's decision and requires fact references.
 *   <li>{@code DomainStrategySpecRenderer} bounds the rendered guidance to {@value
 *       DomainStrategySpecRenderer#MAX_GUIDANCE_LENGTH} characters (DS-07) and always appends the
 *       domain hazards (DS-10).
 *   <li>{@code DomainStrategyResolver} implements the deterministic selection of
 *       DOMAIN_STRATEGY_CONTRACT.md §5 and is called by {@code AnalysisPipeline} (TASK-03);
 *       {@code DomainStrategyConfigurationValidator} fails startup on an ambiguous set (DS-04).
 * </ul>
 */
package com.signalframe.analysis.application.strategies;
