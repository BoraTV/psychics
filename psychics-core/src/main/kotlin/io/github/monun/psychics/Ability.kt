/*
 * Copyright (c) 2020 monun
 *
 *  Licensed under the General Public License, Version 3.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.github.monun.psychics

import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.damage.psychicHeal
import io.github.monun.psychics.format.decimalFormat
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.psychics.util.Times
import io.github.monun.tap.ref.getValue
import io.github.monun.tap.ref.weaky
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.space
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.event.player.PlayerEvent
import java.util.*
import kotlin.math.max

abstract class Ability<T : AbilityConcept> {

    lateinit var concept: T
        private set

    var cooldownTime: Long = 0L
        get() {
            return max(0L, field - Times.current)
        }
        set(value) {
            checkState()

            val times = max(0L, value)
            field = Times.current + times
            updateCooldown((value / 50L).toInt())
        }

    internal fun updateCooldown(ticks: Int = (cooldownTime / 50L).toInt()) {
        val wand = concept.wand
        if (wand != null) esper.player.setCooldown(wand.type, ticks)
    }

    var durationTime: Long = 0L
        get() {
            return max(0L, field - Times.current)
        }
        set(value) {
            checkState()

            val times = max(0L, value)
            field = Times.current + times
        }

    lateinit var psychic: Psychic

    val esper
        get() = psychic.esper

    @Suppress("UNCHECKED_CAST")
    internal fun initConcept(concept: AbilityConcept) {
        this.concept = concept as T
    }

    internal fun initPsychic(psychic: Psychic) {
        val delegate by weaky(psychic)
        this.psychic = delegate
    }

    open fun test(): TestResult {
        val psychic = psychic

        if (!psychic.isEnabled) return TestResult.FailedDisabled
        if (esper.player.level < concept.levelRequirement) return TestResult.FailedLevel
        if (cooldownTime > 0L) return TestResult.FailedCooldown
        if (psychic.mana < concept.cost) return TestResult.FailedCost

        return TestResult.Success
    }

    internal fun save(config: ConfigurationSection) {
        config[COOLDOWN_TIME] = cooldownTime

        runCatching {
            onSave(config)
        }.onFailure {
            it.printStackTrace()
        }
    }

    internal fun load(config: ConfigurationSection) {
        cooldownTime = max(0L, config.getLong(COOLDOWN_TIME))

        runCatching {
            onLoad(config)
        }.onFailure {
            it.printStackTrace()
        }
    }

    companion object {
        private const val COOLDOWN_TIME = "cooldown-time"
    }

    /**
     * ????????? ??? ???????????????.
     */
    open fun onInitialize() {}

    /**
     * ?????????????????? ?????? ??? ???????????????.
     */
    open fun onAttach() {}

    /**
     * ????????????????????? ?????? ??? ???????????????.
     */
    open fun onDetach() {}

    /**
     * ????????? ???????????? ?????? ??? ??? ???????????????.
     */
    open fun onSave(config: ConfigurationSection) {}

    /**
     * ????????? ?????????????????? ?????? ??? ??? ???????????????.
     */
    open fun onLoad(config: ConfigurationSection) {}

    /**
     * ????????? ????????? ??? ??? ???????????????.
     */
    open fun onEnable() {}

    /**
     * ????????? ???????????? ??? ??? ???????????????.
     */
    open fun onDisable() {}

    fun checkState() {
        psychic.checkState()
    }

    fun checkEnabled() {
        psychic.checkEnabled()
    }

    /**
     * ????????? ?????? ??? ????????? ??????????????? ????????? ???????????????.
     */
    fun exhaust() {
        checkEnabled()

        cooldownTime = concept.cooldownTime
        psychic.consumeMana(concept.cost)
    }

    /**
     * [LivingEntity]?????? ????????? ????????????.
     *
     * ?????? ????????? [AbilityConcept]??? ????????? ????????? ???????????????.
     *
     * @exception IllegalArgumentException [AbilityConcept.damage] ????????? ???????????? ?????? ?????? ??? ??????
     */
    fun LivingEntity.psychicDamage(
        damage: Damage = requireNotNull(concept.damage) { "Damage is not defined" },
        knockbackLocation: Location? = esper.player.location,
        knockback: Double = concept.knockback
    ) {
        val type = damage.type
        val amount = esper.getStatistic(damage.stats)

        psychicDamage(this@Ability, type, amount, esper.player, knockbackLocation, knockback)
    }

    /**
     * [LivingEntity]??? ???????????????.
     *
     * ?????? ????????? [AbilityConcept]??? ????????? ????????? ???????????????.
     *
     * @exception IllegalArgumentException [AbilityConcept.healing] ????????? ???????????? ?????? ?????? ??? ??????
     */
    fun LivingEntity.psychicHeal(
        heal: EsperStatistic = requireNotNull(concept.healing) { "Healing is not defined" },
    ) {
        val amount = esper.getStatistic(heal)

        psychicHeal(this@Ability, amount, esper.player)
    }

    /**
     * [LivingEntity]??? ???????????????.
     */
    fun LivingEntity.psychicHeal(
        amount: Double
    ) {
        psychicHeal(this@Ability, amount, esper.player)
    }

}

abstract class ActiveAbility<T : AbilityConcept>(vararg allowActions: WandAction = WandAction.values()) : Ability<T>() {
    val allowActions: Set<WandAction> = EnumSet.copyOf(allowActions.toList())

    var targeter: (() -> Any?)? = null

    override fun test(): TestResult {
        if (psychic.channeling != null) return TestResult.FailedChannel

        return super.test()
    }

    open fun tryCast(
        event: PlayerEvent,
        action: WandAction,
        castingTime: Long = concept.castingTime,
        cost: Double = concept.cost,
        targeter: (() -> Any?)? = this.targeter
    ): TestResult {
        if (action !in allowActions) return TestResult.FailedAction

        val result = test()

        if (result === TestResult.Success) {
            var target: Any? = null

            if (targeter != null) {
                target = targeter.invoke() ?: return TestResult.FailedTarget
            }

            return if (psychic.mana >= concept.cost) {
                cast(event, action, castingTime, target)
                TestResult.Success
            } else {
                TestResult.FailedCost
            }
        }

        return result
    }

    protected fun cast(
        event: PlayerEvent,
        action: WandAction,
        castingTime: Long,
        target: Any? = null
    ) {
        checkState()

        if (castingTime > 0) {
            psychic.startChannel(this, event, action, castingTime, target)
        } else {
            onCast(event, action, target)
        }
    }

    abstract fun onCast(event: PlayerEvent, action: WandAction, target: Any?)

    open fun onChannel(channel: Channel) {}

    open fun onInterrupt(channel: Channel) {}

    enum class WandAction {
        LEFT_CLICK,
        RIGHT_CLICK
    }
}

fun Ability<*>.targetFilter(): TargetFilter {
    return TargetFilter(esper.player)
}

sealed class TestResult {
    object Success : TestResult() {
        override fun message(ability: Ability<*>) = text("??????")
    }

    object FailedLevel : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("????????? ???????????????").decorate(TextDecoration.BOLD)
                .append(space())
                .append(text(ability.concept.levelRequirement))
                .append(text().content("??????"))
                .build()
    }

    object FailedDisabled : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("????????? ?????? ??? ??? ????????????").decorate(TextDecoration.BOLD).build()
    }

    object FailedCooldown : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("?????? ???????????? ???????????????").decorate(TextDecoration.BOLD).build()
    }

    object FailedCost : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("????????? ???????????????").decorate(TextDecoration.BOLD)
                .append(space())
                .append(text(ability.concept.cost.decimalFormat()))
                .build()
    }

    object FailedTarget : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("?????? ?????? ????????? ???????????? ???????????????").decorate(TextDecoration.BOLD)
                .build()
    }

    object FailedChannel : TestResult() {
        override fun message(ability: Ability<*>) =
            text().content("???????????? ????????? ????????????").decorate(TextDecoration.BOLD).build()
    }

    object FailedAction : TestResult() {
        override fun message(ability: Ability<*>) = null
    }

    abstract fun message(ability: Ability<*>): Component?
}