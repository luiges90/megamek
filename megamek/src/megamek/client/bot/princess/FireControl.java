/*
 * MegaMek - Copyright (C) 2000-2011 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */
package megamek.client.bot.princess;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import megamek.common.Aero;
import megamek.common.AmmoType;
import megamek.common.BattleArmor;
import megamek.common.BuildingTarget;
import megamek.common.Compute;
import megamek.common.Coords;
import megamek.common.Entity;
import megamek.common.EntityMovementType;
import megamek.common.EntityWeightClass;
import megamek.common.EquipmentType;
import megamek.common.GunEmplacement;
import megamek.common.IGame;
import megamek.common.IHex;
import megamek.common.Infantry;
import megamek.common.LosEffects;
import megamek.common.Mech;
import megamek.common.MechWarrior;
import megamek.common.Mounted;
import megamek.common.MovePath;
import megamek.common.MoveStep;
import megamek.common.Protomech;
import megamek.common.RangeType;
import megamek.common.Tank;
import megamek.common.TargetRoll;
import megamek.common.Targetable;
import megamek.common.Terrains;
import megamek.common.ToHitData;
import megamek.common.VTOL;
import megamek.common.WeaponType;
import megamek.common.logging.LogLevel;
import megamek.common.weapons.ATMWeapon;
import megamek.common.weapons.MMLWeapon;
import megamek.common.weapons.StopSwarmAttack;
import megamek.common.weapons.infantry.InfantryWeapon;

/**
 * FireControl selects which weapons a unit wants to fire and at whom Pay attention to the difference between "guess"
 * and "get". Guess will be much faster, but inaccurate
 */
public class FireControl {

    private final Princess owner;

    public FireControl(Princess owningPrincess) {
        owner = owningPrincess;
    }

    protected ToHitData getAttackerMovementModifier(IGame game, int shooterId,
                                                    EntityMovementType shooterMoveType) {
        return Compute.getAttackerMovementModifier(game, shooterId, shooterMoveType);
    }

    protected ToHitData getTargetMovementModifier(int hexesMoved, boolean jumping, boolean vtol, IGame game) {
        return Compute.getTargetMovementModifier(hexesMoved, jumping, vtol, game);
    }

    protected static final String TH_ATT_PRONE = "attacker prone";
    protected static final String TH_TAR_IMMOBILE = "target immobile";
    protected static final String TH_TAR_SKID = "target skidded";
    protected static final String TH_TAR_NO_MOVE = "target didn't move";
    protected static final String TH_TAR_SPRINT = "target sprinted";
    protected static final String TH_WOODS = "woods";
    protected static final String TH_TAR_PRONE_RANGE = "target prone and at range";
    protected static final String TH_TAR_PRONE_ADJ = "target prone and adjacent";
    protected static final String TH_TAR_BA = "battle armor target";
    protected static final String TH_TAR_MW = "ejected mechwarrior target";
    protected static final String TH_TAR_INF = "infantry target";
    protected static final String TH_ANTI_AIR = "anti-aircraft quirk";
    protected static final String TH_TAR_LOW_PROFILE = "narrow/low profile target";

    /**
     * Gets the toHit modifier common to both weapon and physical attacks
     */
    public ToHitData guessToHitModifierHelperForAnyAttack(Entity shooter,
                                                          EntityState shooterState,
                                                          Targetable target,
                                                          EntityState targetState,
                                                          IGame game) {

        if (shooterState == null) {
            shooterState = new EntityState(shooter);
        }
        if (targetState == null) {
            targetState = new EntityState(target);
        }
        Entity targetEntity = null;
        if (target instanceof Entity) {
            targetEntity = (Entity) target;
        }

        ToHitData toHitData = new ToHitData();

        // If people are moving or lying down, there are consequences
        toHitData.append(getAttackerMovementModifier(game, shooter.getId(), shooterState.getMovementType()));
        toHitData.append(getTargetMovementModifier(targetState.getHexesMoved(), targetState.isJumping(),
                                                   target instanceof VTOL, game));
        if (shooterState.isProne()) {
            toHitData.addModifier(2, TH_ATT_PRONE);
        }
        if (targetState.isImmobile()) {
            toHitData.addModifier(-4, TH_TAR_IMMOBILE);
        }
        if (game.getOptions().booleanOption("tacops_standing_still")
                && (targetState.getMovementType() == EntityMovementType.MOVE_NONE)
                && !targetState.isImmobile()
                && !((target instanceof Infantry) || (target instanceof VTOL) || (target instanceof
                GunEmplacement))) {
            toHitData.addModifier(-1, TH_TAR_NO_MOVE);
        }

        // did the target sprint?
        if (targetState.getMovementType() == EntityMovementType.MOVE_SPRINT) {
            toHitData.addModifier(-1, TH_TAR_SPRINT);
        }

        // terrain modifiers, since "compute" won't let me do these remotely
        IHex targetHex = game.getBoard().getHex(targetState.getPosition());
        int woodsLevel = targetHex.terrainLevel(Terrains.WOODS);
        if (targetHex.terrainLevel(Terrains.JUNGLE) > woodsLevel) {
            woodsLevel = targetHex.terrainLevel(Terrains.JUNGLE);
        }
        if (woodsLevel == 1) {
            toHitData.addModifier(1, TH_WOODS);
        }
        if (woodsLevel == 2) {
            toHitData.addModifier(2, TH_WOODS);
        }
        if (woodsLevel == 3) {
            toHitData.addModifier(3, TH_WOODS);
        }

        // todo handle smoke.
        // todo handle partial cover.

        int distance = shooterState.getPosition().distance(targetState.getPosition());
        if (targetState.isProne() && (distance > 1)) {
            toHitData.addModifier(1, TH_TAR_PRONE_RANGE);
        } else if (targetState.isProne() && (distance == 1)) {
            toHitData.addModifier(-2, TH_TAR_PRONE_ADJ);
        }

        if (targetState.getMovementType() == EntityMovementType.MOVE_SKID) {
            toHitData.addModifier(2, TH_TAR_SKID);
        }

        boolean isShooterInfantry = (shooter instanceof Infantry);
        if (!isShooterInfantry) {
            if (target instanceof BattleArmor) {
                toHitData.addModifier(1, TH_TAR_BA);
            } else if (target instanceof MechWarrior) {
                toHitData.addModifier(2, TH_TAR_MW);
            } else if (target instanceof Infantry) {
                toHitData.addModifier(1, TH_TAR_INF);
            }
        }

        if (shooter.hasQuirk("anti_air") && (target.isAirborne() || target.isAirborneVTOLorWIGE())) {
            toHitData.addModifier(-2, TH_ANTI_AIR);
        }

        // todo improved targetting (range), variable range targetting, poor targetting (range), accurate, inaccurate

        if (targetEntity != null) {
            if (targetEntity.hasQuirk("low_profile")) {
                toHitData.addModifier(1, TH_TAR_LOW_PROFILE);
            }
        }

        return toHitData;
    }

    /**
     * Makes a rather poor guess as to what the to hit modifier will be with a
     * physical attack.
     */
    public ToHitData guessToHitModifier_Physical(Entity shooter,
                                                 EntityState shooter_state, Targetable target,
                                                 EntityState target_state, PhysicalAttackType attack_type,
                                                 IGame game) {
        final String METHOD_NAME = "guessToHitModifier(Entity, EntityState, Targetable, EntityState, " +
                "PhysicalAttackType, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            if (!(shooter instanceof Mech)) {
                return new ToHitData(TargetRoll.IMPOSSIBLE,
                                     "Non mechs don't make physical attacks");
            }
            // Base to hit is piloting skill +2
            ToHitData tohit = new ToHitData();
            if (shooter_state == null) {
                shooter_state = new EntityState(shooter);
            }
            if (target_state == null) {
                target_state = new EntityState(target);
            }
            int distance = shooter_state.getPosition().distance(target_state.getPosition());
            if (distance > 1) {
                return new ToHitData(TargetRoll.IMPOSSIBLE, "Can't hit that far");
            }

            tohit.append(guessToHitModifierHelperForAnyAttack(shooter, shooter_state,
                                                              target, target_state, game));
            // check if target is within arc
            int arc = 0;
            if (attack_type == PhysicalAttackType.LEFT_PUNCH) {
                arc = Compute.ARC_LEFTARM;
            } else if (attack_type == PhysicalAttackType.RIGHT_PUNCH) {
                arc = Compute.ARC_RIGHTARM;
            } else {
                arc = Compute.ARC_FORWARD; // assume kick
            }
            if (!(Compute.isInArc(shooter_state.getPosition(),
                                  shooter_state.getSecondaryFacing(), target_state.getPosition(),
                                  arc) || (distance == 0))) {
                return new ToHitData(TargetRoll.IMPOSSIBLE, "Target not in arc");
            }

            IHex attHex = game.getBoard().getHex(shooter_state.getPosition());
            IHex targHex = game.getBoard().getHex(target_state.getPosition());
            final int attackerElevation = shooter.getElevation()
                    + attHex.getElevation();
            final int attackerHeight = shooter.absHeight() + attHex.getElevation();
            final int targetElevation = target.getElevation()
                    + targHex.getElevation();
            final int targetHeight = targetElevation + target.getHeight();
            if ((attack_type == PhysicalAttackType.LEFT_PUNCH)
                    || (attack_type == PhysicalAttackType.RIGHT_PUNCH)) {
                if ((attackerHeight < targetElevation)
                        || (attackerHeight > targetHeight)) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "Target elevation not in range");
                }

                if (shooter_state.isProne()) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "can't punch while prone");
                }
                if (target instanceof Infantry) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "can't punch infantry");
                }
                int armLoc = attack_type == PhysicalAttackType.RIGHT_PUNCH ? Mech.LOC_RARM
                        : Mech.LOC_LARM;
                if (shooter.isLocationBad(armLoc)) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE, "Your arm's off!");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_SHOULDER, armLoc)) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "shoulder destroyed");
                }
                tohit.addModifier(shooter.getCrew().getPiloting(), "base");
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_UPPER_ARM, armLoc)) {
                    tohit.addModifier(2, "Upper arm actuator destroyed");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_LOWER_ARM, armLoc)) {
                    tohit.addModifier(2, "Lower arm actuator missing or destroyed");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_HAND, armLoc)) {
                    tohit.addModifier(1, "Hand actuator missing or destroyed");
                }
            } else // assuming kick
            {
                tohit.addModifier(shooter.getCrew().getPiloting() - 2, "base");
                if (shooter_state.isProne()) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "Can't kick while prone");
                }
                if (target instanceof Infantry) {
                    if (distance == 0) {
                        tohit.addModifier(3, "kicking infantry");
                    } else {
                        return new ToHitData(TargetRoll.IMPOSSIBLE,
                                             "Infantry too far away");
                    }
                }
                if ((attackerElevation < targetElevation)
                        || (attackerElevation > targetHeight)) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "Target elevation not in range");
                }
                int legLoc = attack_type == PhysicalAttackType.RIGHT_KICK ? Mech.LOC_RLEG
                        : Mech.LOC_LLEG;
                if (((Mech) shooter).hasHipCrit()) {
                    // if (!shooter.hasWorkingSystem(Mech.ACTUATOR_HIP,
                    // legLoc)||!shooter.hasWorkingSystem(Mech.ACTUATOR_HIP,otherLegLoc))
                    // {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "can't kick with broken hip");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_UPPER_LEG, legLoc)) {
                    tohit.addModifier(2, "Upper leg actuator destroyed");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_LOWER_LEG, legLoc)) {
                    tohit.addModifier(2, "Lower leg actuator destroyed");
                }
                if (!shooter.hasWorkingSystem(Mech.ACTUATOR_FOOT, legLoc)) {
                    tohit.addModifier(1, "Foot actuator destroyed");
                }
                if (game.getOptions().booleanOption("tacops_attack_physical_psr")) {
                    if (shooter.getWeightClass() == EntityWeightClass.WEIGHT_LIGHT) {
                        tohit.addModifier(-2, "Weight Class Attack Modifier");
                    } else if (shooter.getWeightClass() == EntityWeightClass.WEIGHT_MEDIUM) {
                        tohit.addModifier(-1, "Weight Class Attack Modifier");
                    }
                }

            }
            return tohit;
        } finally {
            owner.methodEnd(FireControl.class, METHOD_NAME);
        }
    }

    /**
     * Makes an educated guess as to the to hit modifier with a weapon attack.
     * Does not actually place unit into desired position, because that is
     * exceptionally slow. Most of this is copied from WeaponAttack.
     */
    public ToHitData guessToHitModifier(Entity shooter,
                                        EntityState shooter_state, Targetable target,
                                        EntityState target_state, Mounted mw, IGame game, Princess owner) {
        final String METHOD_NAME = "guessToHitModifier(Entity, EntityState, Targetable, EntityState, Mounted, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            if (shooter_state == null) {
                shooter_state = new EntityState(shooter);
            }
            if (target_state == null) {
                target_state = new EntityState(target);
            }
            // first check if the shot is impossible
            if (!mw.canFire()) {
                return new ToHitData(TargetRoll.IMPOSSIBLE, "weapon cannot fire");
            }
            if (((WeaponType) mw.getType()).ammoType != AmmoType.T_NA) {
                if (mw.getLinked() == null) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE, "ammo is gone");
                }
                if (mw.getLinked().getUsableShotsLeft() == 0) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "weapon out of ammo");
                }
            }
            if ((shooter_state.isProne())
                    && ((shooter.isLocationBad(Mech.LOC_RARM)) || (shooter
                    .isLocationBad(Mech.LOC_LARM)))) {
                return new ToHitData(TargetRoll.IMPOSSIBLE,
                                     "prone and missing an arm.");
            }

            int shooter_facing = shooter_state.getFacing();
            if (shooter.isSecondaryArcWeapon(shooter.getEquipmentNum(mw))) {
                shooter_facing = shooter_state.getSecondaryFacing(); // check if torso
            }
            // twists affect
            // weapon
            boolean inarc = Compute.isInArc(shooter_state.getPosition(), shooter_facing,
                                            target_state.getPosition(),
                                            shooter.getWeaponArc(shooter.getEquipmentNum(mw)));
            if (!inarc) {
                return new ToHitData(TargetRoll.IMPOSSIBLE, "not in arc");
            }
            // Find out a bit about the shooter and target
            boolean isShooterInfantry = (shooter instanceof Infantry);
            boolean isWeaponInfantry = ((WeaponType) mw.getType())
                    .hasFlag(WeaponType.F_INFANTRY);
            if ((shooter_state.getPosition() == null) || (target_state.getPosition() == null)) {
                return new ToHitData(TargetRoll.AUTOMATIC_FAIL, "null position");
            }
            int distance = shooter_state.getPosition().distance(target_state.getPosition());

            if ((distance == 0) && (!isShooterInfantry)) {
                return new ToHitData(TargetRoll.AUTOMATIC_FAIL,
                                     "noninfantry shooting with zero range");
            }
            // Base to hit is gunnery skill
            ToHitData tohit = new ToHitData(shooter.getCrew().getGunnery(),
                                            "gunnery skill");
            tohit.append(guessToHitModifierHelperForAnyAttack(shooter, shooter_state,
                                                              target, target_state, game));
            // There is kindly already a class that will calculate line of sight for
            // me
            LosEffects loseffects = LosEffects.calculateLos(game, shooter.getId(),
                                                            target, shooter_state.getPosition(),
                                                            target_state.getPosition(), false);
            // water is a separate loseffect
            IHex target_hex = game.getBoard().getHex(target_state.getPosition());
            if (target instanceof Entity) {
                if (target_hex.containsTerrain(Terrains.WATER)
                        && (target_hex.terrainLevel(Terrains.WATER) == 1)
                        && (((Entity) target).height() > 0)) {
                    loseffects.setTargetCover(loseffects.getTargetCover()
                                                      | LosEffects.COVER_HORIZONTAL);
                }
            }
            tohit.append(loseffects.losModifiers(game));
            if ((tohit.getValue() == TargetRoll.IMPOSSIBLE)
                    || (tohit.getValue() == TargetRoll.AUTOMATIC_FAIL)) {
                return tohit; // you can't hit what you can't see
            }
            // deal with some special cases
            if (((WeaponType) mw.getType()) instanceof StopSwarmAttack) {
                if (Entity.NONE == shooter.getSwarmTargetId()) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "Not swarming a Mek.");
                } else {
                    return new ToHitData(TargetRoll.AUTOMATIC_SUCCESS,
                                         "stops swarming");
                }
            }
            if (shooter instanceof Tank) {
                int sensors = ((Tank) shooter).getSensorHits();
                if (sensors > 0) {
                    tohit.addModifier(sensors, "sensor damage");
                }
            }

            if (target instanceof Mech) {
                if (Infantry.SWARM_MEK.equals(mw.getType().getInternalName())) {
                    tohit.append(Compute.getSwarmMekBaseToHit(shooter,
                                                              (Entity) target, game));
                }
                if (Infantry.LEG_ATTACK.equals(mw.getType().getInternalName())) {
                    tohit.append(Compute.getLegAttackBaseToHit(shooter,
                                                               (Entity) target, game));
                }
            }
            if ((tohit.getValue() == TargetRoll.IMPOSSIBLE)
                    || (tohit.getValue() == TargetRoll.AUTOMATIC_FAIL)) {
                return tohit;
            }
            // Now deal with range effects
            int range = RangeType.rangeBracket(distance,
                                               ((WeaponType) mw.getType()).getRanges(mw),
                                               game.getOptions().booleanOption("tacops_range"));
            // Aeros are 2x further for each altitude
            if (target instanceof Aero) {
                range += 2 * target.getAltitude();
            }
            if (!isWeaponInfantry) {
                if (range == RangeType.RANGE_SHORT) {
                    tohit.addModifier(0, "Short Range");
                } else if (range == RangeType.RANGE_MEDIUM) {
                    tohit.addModifier(2, "Medium Range");
                } else if (range == RangeType.RANGE_LONG) {
                    tohit.addModifier(4, "Long Range");
                } else if (range == RangeType.RANGE_MINIMUM) {
                    tohit.addModifier(
                            (((WeaponType) mw.getType()).getMinimumRange() - distance) + 1,
                            "Minimum Range");
                } else {
                    return new ToHitData(TargetRoll.AUTOMATIC_FAIL, "out of range"); // out
                    // of
                    // range
                }
            } else {
                tohit.append(Compute.getInfantryRangeMods(distance,
                                                          (InfantryWeapon) mw.getType()));
            }

            // let us not forget about heat
            if (shooter.getHeatFiringModifier() != 0) {
                tohit.addModifier(shooter.getHeatFiringModifier(), "heat");
            }
            // and damage
            tohit.append(Compute.getDamageWeaponMods(shooter, mw));
            // and finally some more special cases
            if (((WeaponType) mw.getType()).getToHitModifier() != 0) {
                tohit.addModifier(((WeaponType) mw.getType()).getToHitModifier(),
                                  "weapon to-hit");
            }
            if (((WeaponType) mw.getType()).getAmmoType() != AmmoType.T_NA) {
                AmmoType atype = (AmmoType) mw.getLinked().getType();
                if ((atype != null) && (atype.getToHitModifier() != 0)) {
                    tohit.addModifier(atype.getToHitModifier(),
                                      "ammunition to-hit modifier");
                }
            }
            if (shooter.hasTargComp()
                    && ((WeaponType) mw.getType())
                    .hasFlag(WeaponType.F_DIRECT_FIRE)) {
                tohit.addModifier(-1, "targeting computer");
            }

            return tohit;
        } finally {
            owner.methodEnd(FireControl.class, METHOD_NAME);
        }
    }

    /**
     * Makes an educated guess as to the to hit modifier by an aerospace unit
     * flying on a ground map doing a strike attack on a unit
     */
    public ToHitData guessAirToGroundStrikeToHitModifier(Entity shooter,
                                                         Targetable target, EntityState target_state,
                                                         MovePath shooter_path,
                                                         Mounted mw, IGame game,
                                                         boolean assume_under_flight_plan) {
        final String METHOD_NAME = "guessAirToGroundStrikeToHitModifier(Entity, Targetable, EntityState, MovePath, " +
                "Mounted, IGame, boolean)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            if (target_state == null) {
                target_state = new EntityState(target);
            }
            EntityState shooter_state = new EntityState(shooter);
            // first check if the shot is impossible
            if (!mw.canFire()) {
                return new ToHitData(TargetRoll.IMPOSSIBLE, "weapon cannot fire");
            }
            if (((WeaponType) mw.getType()).ammoType != AmmoType.T_NA) {
                if (mw.getLinked() == null) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE, "ammo is gone");
                }
                if (mw.getLinked().getUsableShotsLeft() == 0) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "weapon out of ammo");
                }
            }
            // check if target is even under our path
            if (!assume_under_flight_plan) {
                if (!isTargetUnderMovePath(shooter_path, target_state)) {
                    return new ToHitData(TargetRoll.IMPOSSIBLE,
                                         "target not under flight path");
                }
            }
            // Base to hit is gunnery skill
            ToHitData tohit = new ToHitData(shooter.getCrew().getGunnery(),
                                            "gunnery skill");
            tohit.append(guessToHitModifierHelperForAnyAttack(shooter, shooter_state,
                                                              target, target_state, game));
            // Additional penalty due to strike attack
            tohit.addModifier(+2, "strike attack");

            return tohit;
        } finally {
            owner.methodEnd(FireControl.class, METHOD_NAME);
        }
    }

    /**
     * Checks if a target lies under a move path, to see if an aero unit can
     * attack it
     *
     * @param p            move path to check
     * @param target_state used for targets position
     * @return
     */
    public boolean isTargetUnderMovePath(MovePath p,
                                         EntityState target_state) {
        final String METHOD_NAME = "isTargetUnderMovePath(MovePath, EntityState)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            for (Enumeration<MoveStep> e = p.getSteps(); e.hasMoreElements(); ) {
                Coords cord = e.nextElement().getPosition();
                if (cord.equals(target_state.getPosition())) {
                    return true;
                }
            }
            return false;
        } finally {
            owner.methodEnd(FireControl.class, METHOD_NAME);
        }
    }

    /**
     * Returns a list of enemies that lie under this flight path
     *
     * @param p
     * @param shooter
     * @param game
     * @return
     */
    ArrayList<Entity> getEnemiesUnderFlightPath(MovePath p, Entity shooter,
                                                IGame game) {
        final String METHOD_NAME = "getEnemiesUnderFlightPath(MovePath, Entity, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            ArrayList<Entity> ret = new ArrayList<Entity>();
            for (Enumeration<MoveStep> e = p.getSteps(); e.hasMoreElements(); ) {
                Coords cord = e.nextElement().getPosition();
                Entity enemy = game.getFirstEnemyEntity(cord, shooter);
                if (enemy != null) {
                    ret.add(enemy);
                }
            }
            return ret;
        } finally {
            owner.methodEnd(FireControl.class, METHOD_NAME);
        }
    }

    /**
     * Mostly for debugging, this returns a non-null string that describes how
     * the guess has failed to be perfectly accurate. or null if perfectly
     * accurate
     */
    String checkGuess(Entity shooter, Targetable target, Mounted mw, IGame game) {
        final String METHOD_NAME = "checkGuess(Entity, Targetable, Mounted, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {

            if ((shooter instanceof Aero) ||
                    (shooter.getPosition() == null) ||
                    (target.getPosition() == null)) {
                return null;
            }
            String ret = null;
            WeaponFireInfo guess_info = new WeaponFireInfo(shooter,
                                                           new EntityState(shooter), target, null, mw, game, owner);
            WeaponFireInfo accurate_info = new WeaponFireInfo(shooter, target, mw,
                                                              game, owner);
            if (guess_info.getToHit().getValue() != accurate_info.getToHit().getValue()) {
                ret = new String();
                ret += "Incorrect To Hit prediction, weapon " + mw.getName() + " ("
                        + shooter.getChassis() + " vs " + target.getDisplayName()
                        + ")" + ":\n";
                ret += " Guess: " + Integer.toString(guess_info.getToHit().getValue())
                        + " " + guess_info.getToHit().getDesc() + "\n";
                ret += " Real:  "
                        + Integer.toString(accurate_info.getToHit().getValue()) + " "
                        + accurate_info.getToHit().getDesc() + "\n";
            }
            return ret;
        } finally {
            owner.methodEnd(getClass(), METHOD_NAME);
        }
    }

    /**
     * Mostly for debugging, this returns a non-null string that describes how
     * the guess on a physical attack failed to be perfectly accurate, or null
     * if accurate
     */
    String checkGuess_Physical(Entity shooter, Targetable target,
                               PhysicalAttackType attack_type, IGame game) {
        final String METHOD_NAME = "getGuess_Physical(Entity, Targetable, PhysicalAttackType, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            if (!(shooter instanceof Mech)) {
                return null; // only mechs can do physicals
            }

            String ret = null;
            if (shooter.getPosition() == null) {
                return "Shooter has NULL coordinates!";
            } else if (target.getPosition() == null) {
                return "Target has NULL coordinates!";
            }
            PhysicalInfo guess_info = new PhysicalInfo(shooter, null, target, null,
                                                       attack_type, game, owner);
            PhysicalInfo accurate_info = new PhysicalInfo(shooter, target,
                                                          attack_type, game, owner);
            if (guess_info.to_hit.getValue() != accurate_info.to_hit.getValue()) {
                ret = new String();
                ret += "Incorrect To Hit prediction, physical attack "
                        + attack_type.name() + ":\n";
                ret += " Guess: " + Integer.toString(guess_info.to_hit.getValue())
                        + " " + guess_info.to_hit.getDesc() + "\n";
                ret += " Real:  "
                        + Integer.toString(accurate_info.to_hit.getValue()) + " "
                        + accurate_info.to_hit.getDesc() + "\n";
            }
            return ret;
        } finally {
            owner.methodEnd(getClass(), METHOD_NAME);
        }
    }

    /**
     * Mostly for debugging, this returns a non-null string that describes how
     * any possible guess has failed to be perfectly accurate. or null if
     * perfect
     */
    String checkAllGuesses(Entity shooter, IGame game) {
        final String METHOD_NAME = "checkAllGuesses(Entity, IGame)";
        owner.methodBegin(FireControl.class, METHOD_NAME);

        try {
            String ret = new String("");
            ArrayList<Targetable> enemies = getTargetableEnemyEntities(shooter,
                                                                       game);
            for (Targetable e : enemies) {
                for (Mounted mw : shooter.getWeaponList()) {
                    String splain = checkGuess(shooter, e, mw, game);
                    if (splain != null) {
                        ret += splain;
                    }
                }
                String splainphys = null;
                splainphys = checkGuess_Physical(shooter, e,
                                                 PhysicalAttackType.RIGHT_KICK, game);
                if (splainphys != null) {
                    ret += splainphys;
                }
                splainphys = checkGuess_Physical(shooter, e,
                                                 PhysicalAttackType.LEFT_KICK, game);
                if (splainphys != null) {
                    ret += splainphys;
                }
                splainphys = checkGuess_Physical(shooter, e,
                                                 PhysicalAttackType.RIGHT_PUNCH, game);
                if (splainphys != null) {
                    ret += splainphys;
                }
                splainphys = checkGuess_Physical(shooter, e,
                                                 PhysicalAttackType.LEFT_PUNCH, game);
                if (splainphys != null) {
                    ret += splainphys;
                }

            }
            if (ret.compareTo("") == 0) {
                return null;
            }
            return ret;
        } finally {
            owner.methodEnd(getClass(), METHOD_NAME);
        }
    }

    /**
     * calculates the 'utility' of a firing plan. override this function if you
     * have a better idea about what firing plans are good
     */
    void calculateUtility(FiringPlan p, int overheat_value) {
        double damage_utility = 1.0;
        double critical_utility = 10.0;
        double kill_utility = 50.0;
        double overheat_disutility = 5.0;
        double ejected_pilot_disutility = (p.getTarget() instanceof MechWarrior ? 1000.0 : 0.0);
        int overheat = 0;
        if (p.getHeat() > overheat_value) {
            overheat = p.getHeat() - overheat_value;
        }
        p.setUtility(((damage_utility * p.getExpectedDamage())
                + (critical_utility * p.getExpectedCriticals()) + (kill_utility * p
                .getKillProbability())) - (overheat_disutility * overheat) - ejected_pilot_disutility);
    }

    /**
     * calculates the 'utility' of a physical action.
     */
    void calculateUtility(PhysicalInfo p) {
        double damage_utility = 1.0;
        double critical_utility = 10.0;
        double kill_utility = 50.0;
        p.utility = (damage_utility * p.getExpectedDamage())
                + (critical_utility * p.expected_criticals)
                + (kill_utility * p.kill_probability);
    }

    /**
     * Creates a firing plan that fires all weapons with nonzero to hit value at
     * a target ignoring heat, and using best guess from different states Does
     * not change facing
     */
    FiringPlan guessFullFiringPlan(Entity shooter, EntityState shooter_state,
                                   Targetable target, EntityState target_state, IGame game) {
        if (shooter_state == null) {
            shooter_state = new EntityState(shooter);
        }
        FiringPlan myplan = new FiringPlan(owner, target);
        if (shooter.getPosition() == null) {
            owner.log(getClass(), "guessFullFiringPlan(Entity, EntityState, Targetable, EntityState, IGame)",
                      LogLevel.ERROR, "Shooter's position is NULL!");
            return myplan;
        }
        if (target.getPosition() == null) {
            owner.log(getClass(), "guessFullFiringPlan(Entity, EntityState, Targetable, EntityState, IGame)",
                      LogLevel.ERROR, "Target's position is NULL!");
            return myplan;
        }
        for (Mounted mw : shooter.getWeaponList()) { // cycle through my weapons
            WeaponFireInfo shoot = new WeaponFireInfo(shooter, shooter_state,
                                                      target, target_state, mw, game, owner);
            if (shoot.getProbabilityToHit() > 0) {
                myplan.add(shoot);
            }
        }
        calculateUtility(
                myplan,
                (shooter instanceof Mech) ? ((shooter.getHeatCapacity() - shooter_state.getHeat()) + 5)
                        : 999);
        return myplan;
    }

    /**
     * Creates a firing plan that fires all weapons with nonzero to hit value in
     * a air to ground strike
     *
     * @param shooter
     * @param target
     * @param target_state
     * @param shooter_path
     * @param game
     * @param assume_under_flight_path
     * @return
     */
    FiringPlan guessFullAirToGroundPlan(Entity shooter, Targetable target,
                                        EntityState target_state, MovePath shooter_path, IGame game,
                                        boolean assume_under_flight_path) {
        if (target_state == null) {
            target_state = new EntityState(target);
        }
        if (!assume_under_flight_path) {
            if (!isTargetUnderMovePath(shooter_path, target_state)) {
                return new FiringPlan(owner, target);
            }
        }
        FiringPlan myplan = new FiringPlan(owner, target);
        if (shooter.getPosition() == null) {
            owner.log(getClass(),
                      "guessFullAirToGroundPlan(Entity, Targetable, EntityState, MovePath, IGame, boolean)",
                      LogLevel.ERROR, "Shooter's position is NULL!");
            return myplan;
        }
        if (target.getPosition() == null) {
            owner.log(getClass(),
                      "guessFullAirToGroundPlan(Entity, Targetable, EntityState, MovePath, IGame, boolean)",
                      LogLevel.ERROR, "Target's position is NULL!");
            return myplan;
        }
        for (Mounted mw : shooter.getWeaponList()) { // cycle through my weapons

            WeaponFireInfo shoot = new WeaponFireInfo(shooter, shooter_path,
                                                      target, target_state, mw, game, true, owner);
            if (shoot.getProbabilityToHit() > 0) {
                myplan.add(shoot);
            }
        }
        calculateUtility(myplan, 999); // Aeros don't have heat capacity, (I
        // think?)
        return myplan;
    }

    /**
     * Guesses what the expected damage would be if the shooter fired all of its
     * weapons at the target
     */
    double guessExpectedDamage(Entity shooter, EntityState shooter_state,
                               Targetable target, EntityState target_state, IGame game) {
        // FiringPlan
        // fullplan=guessFullFiringPlan(shooter,shooter_state,target,target_state,game);
        FiringPlan fullplan = guessFullFiringPlan(shooter, shooter_state,
                                                  target, target_state, game);
        return fullplan.getExpectedDamage();
    }

    /**
     * Creates a firing plan that fires all weapons with nonzero to hit value at
     * a target ignoring heat, and using actual game ruleset from different
     * states
     */
    FiringPlan getFullFiringPlan(Entity shooter, Targetable target, IGame game) {
        FiringPlan myplan = new FiringPlan(owner, target);
        if (shooter.getPosition() == null) {
            owner.log(getClass(),
                      "getFullFiringPlan(Entity, Targetable, IGame)", LogLevel.ERROR,
                      "Shooter's position is NULL!");
            return myplan;
        }
        if (target.getPosition() == null) {
            owner.log(getClass(),
                      "getFullFiringPlan(Entity, Targetable, IGame)", LogLevel.ERROR,
                      "Target's position is NULL!");
            return myplan;
        }
        for (Mounted mw : shooter.getWeaponList()) { // cycle through my weapons
            WeaponFireInfo shoot = new WeaponFireInfo(shooter, target, mw, game, owner);
            if ((shoot.getProbabilityToHit() > 0)) {
                myplan.add(shoot);
            }
        }
        calculateUtility(myplan, (shooter.getHeatCapacity() - shooter.heat) + 5);
        return myplan;
    }

    /**
     * Creates an array that gives the 'best' firing plan (the maximum utility)
     * under the heat of the index
     */
    FiringPlan[] calcFiringPlansUnderHeat(FiringPlan maxplan, int maxheat, Targetable target,
                                          IGame game) {
        if (maxheat < 0) {
            maxheat = 0; // can't be worse than zero heat
        }
        FiringPlan[] best_plans = new FiringPlan[maxheat + 1];
        best_plans[0] = new FiringPlan(owner, target);
        FiringPlan nonzeroheat_options = new FiringPlan(owner, target);
        // first extract any firings of zero heat
        for (WeaponFireInfo f : maxplan) {
            if (f.getHeat() == 0) {
                best_plans[0].add(f);
            } else {
                nonzeroheat_options.add(f);
            }
        }
        // build up heat table
        for (int i = 1; i <= maxheat; i++) {
            best_plans[i] = new FiringPlan(owner, target);
            best_plans[i].addAll(best_plans[i - 1]);
            for (WeaponFireInfo f : nonzeroheat_options) {
                if ((i - f.getHeat()) >= 0) {
                    if (!best_plans[i - f.getHeat()].containsWeapon(f.getWeapon())) {
                        FiringPlan testplan = new FiringPlan(owner, target);
                        testplan.addAll(best_plans[i - f.getHeat()]);
                        testplan.add(f);
                        calculateUtility(testplan, 999); // TODO fix overheat
                        if (testplan.getUtility() > best_plans[i].getUtility()) {
                            best_plans[i] = testplan;
                        }
                    }
                }
            }
        }
        return best_plans;
    }

    /**
     * Gets the 'best' firing plan under a certain heat No twisting is done
     */
    FiringPlan getBestFiringPlanUnderHeat(Entity shooter, Targetable target,
                                          int maxheat, IGame game) {
        if (maxheat < 0) {
            maxheat = 0; // can't have less than zero heat
        }
        FiringPlan fullplan = getFullFiringPlan(shooter, target, game);
        if (fullplan.getHeat() <= maxheat) {
            return fullplan;
        }
        FiringPlan heatplans[] = calcFiringPlansUnderHeat(fullplan, maxheat, target, game);
        return heatplans[maxheat];
    }

    /*
     * Gets the 'best' firing plan, using heat as a disutility. No twisting is
     * done
     */
    FiringPlan getBestFiringPlan(Entity shooter, Targetable target, IGame game) {
        FiringPlan fullplan = getFullFiringPlan(shooter, target, game);
        if (!(shooter instanceof Mech)) {
            return fullplan; // no need to optimize heat for non-mechs
        }
        FiringPlan heatplans[] = calcFiringPlansUnderHeat(fullplan, fullplan.getHeat(), target, game);
        FiringPlan best_plan = new FiringPlan(owner, target);
        int overheat = (shooter.getHeatCapacity() - shooter.heat) + 4;
        for (int i = 0; i < (fullplan.getHeat() + 1); i++) {
            calculateUtility(heatplans[i], overheat);
            if ((best_plan.getUtility() < heatplans[i].getUtility())) {
                best_plan = heatplans[i];
            }
        }
        return best_plan;
    }

    /**
     * Guesses the 'best' firing plan under a certain heat No twisting is done
     */
    FiringPlan guessBestFiringPlanUnderHeat(Entity shooter,
                                            EntityState shooter_state, Targetable target,
                                            EntityState target_state, int maxheat, IGame game) {
        if (maxheat < 0) {
            maxheat = 0; // can't have less than zero heat
        }
        FiringPlan fullplan = guessFullFiringPlan(shooter, shooter_state,
                                                  target, target_state, game);
        if (fullplan.getHeat() <= maxheat) {
            return fullplan;
        }
        FiringPlan heatplans[] = calcFiringPlansUnderHeat(fullplan, maxheat, target, game);
        return heatplans[maxheat];
    }

    /**
     * Guesses the 'best' firing plan, using heat as a disutility. No twisting
     * is done
     */
    FiringPlan guessBestFiringPlan(Entity shooter, EntityState shooter_state,
                                   Targetable target, EntityState target_state, IGame game) {
        if (shooter_state == null) {
            shooter_state = new EntityState(shooter);
        }
        FiringPlan fullplan = guessFullFiringPlan(shooter, shooter_state,
                                                  target, target_state, game);
        if (!(shooter instanceof Mech)) {
            return fullplan; // no need to optimize heat for non-mechs
        }
        FiringPlan heatplans[] = calcFiringPlansUnderHeat(fullplan, fullplan.getHeat(), target, game);
        FiringPlan best_plan = new FiringPlan(owner, target);
        int overheat = (shooter.getHeatCapacity() - shooter_state.getHeat()) + 4;
        for (int i = 0; i < fullplan.getHeat(); i++) {
            calculateUtility(heatplans[i], overheat);
            if ((best_plan.getUtility() < heatplans[i].getUtility())) {
                best_plan = heatplans[i];
            }
        }
        return best_plan;
    }

    /**
     * Gets the 'best' firing plan under a certain heat includes the option of
     * twisting
     */
    FiringPlan getBestFiringPlanUnderHeatWithTwists(Entity shooter,
                                                    Targetable target, int maxheat, IGame game) {
        int orig_facing = shooter.getSecondaryFacing();
        FiringPlan notwist_plan = getBestFiringPlanUnderHeat(shooter, target,
                                                             maxheat, game);
        if (!shooter.canChangeSecondaryFacing()) {
            return notwist_plan;
        }
        shooter.setSecondaryFacing(correct_facing(orig_facing + 1));
        FiringPlan righttwist_plan = getBestFiringPlanUnderHeat(shooter,
                                                                target, maxheat, game);
        righttwist_plan.twist = 1;
        shooter.setSecondaryFacing(correct_facing(orig_facing - 1));
        FiringPlan lefttwist_plan = getBestFiringPlanUnderHeat(shooter, target,
                                                               maxheat, game);
        lefttwist_plan.twist = -1;
        shooter.setSecondaryFacing(orig_facing);
        if ((notwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage())
                && (notwist_plan.getExpectedDamage() > lefttwist_plan
                .getExpectedDamage())) {
            return notwist_plan;
        }
        if (lefttwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage()) {
            return lefttwist_plan;
        }
        return righttwist_plan;
    }

    /**
     * Gets the 'best' firing plan using heat as disutiltiy includes the option
     * of twisting
     */
    FiringPlan getBestFiringPlanWithTwists(Entity shooter, Targetable target,
                                           IGame game) {
        int orig_facing = shooter.getSecondaryFacing();
        FiringPlan notwist_plan = getBestFiringPlan(shooter, target, game);
        if (!shooter.canChangeSecondaryFacing()) {
            return notwist_plan;
        }
        shooter.setSecondaryFacing(correct_facing(orig_facing + 1));
        FiringPlan righttwist_plan = getBestFiringPlan(shooter, target, game);
        righttwist_plan.twist = 1;
        shooter.setSecondaryFacing(correct_facing(orig_facing - 1));
        FiringPlan lefttwist_plan = getBestFiringPlan(shooter, target, game);
        lefttwist_plan.twist = -1;
        shooter.setSecondaryFacing(orig_facing);
        if ((notwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage())
                && (notwist_plan.getExpectedDamage() > lefttwist_plan
                .getExpectedDamage())) {
            return notwist_plan;
        }
        if (lefttwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage()) {
            return lefttwist_plan;
        }
        return righttwist_plan;
    }

    /**
     * Guesses the 'best' firing plan under a certain heat includes the option
     * of twisting
     */
    FiringPlan guessBestFiringPlanUnderHeatWithTwists(Entity shooter,
                                                      EntityState shooter_state, Targetable target,
                                                      EntityState target_state, int maxheat, IGame game) {
        if (shooter_state == null) {
            shooter_state = new EntityState(shooter);
        }
        int orig_facing = shooter_state.getFacing();
        FiringPlan notwist_plan = guessBestFiringPlanUnderHeat(shooter,
                                                               shooter_state, target, target_state, maxheat, game);
        if (!shooter.canChangeSecondaryFacing()) {
            return notwist_plan;
        }
        shooter_state.setSecondaryFacing(correct_facing(orig_facing + 1));
        FiringPlan righttwist_plan = guessBestFiringPlanUnderHeat(shooter,
                                                                  shooter_state, target, target_state, maxheat, game);
        righttwist_plan.twist = 1;
        shooter_state.setSecondaryFacing(correct_facing(orig_facing - 1));
        FiringPlan lefttwist_plan = guessBestFiringPlanUnderHeat(shooter,
                                                                 shooter_state, target, target_state, maxheat, game);
        lefttwist_plan.twist = -1;
        shooter_state.setSecondaryFacing(orig_facing);
        if ((notwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage())
                && (notwist_plan.getExpectedDamage() > lefttwist_plan
                .getExpectedDamage())) {
            return notwist_plan;
        }
        if (lefttwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage()) {
            return lefttwist_plan;
        }
        return righttwist_plan;
    }

    /**
     * Guesses the 'best' firing plan under a certain heat includes the option
     * of twisting
     */
    FiringPlan guessBestFiringPlanWithTwists(Entity shooter,
                                             EntityState shooter_state, Targetable target,
                                             EntityState target_state, IGame game) {
        if (shooter_state == null) {
            shooter_state = new EntityState(shooter);
        }
        int orig_facing = shooter_state.getFacing();
        FiringPlan notwist_plan = guessBestFiringPlan(shooter, shooter_state,
                                                      target, target_state, game);
        if (!shooter.canChangeSecondaryFacing()) {
            return notwist_plan;
        }
        shooter_state.setSecondaryFacing(correct_facing(orig_facing + 1));
        FiringPlan righttwist_plan = guessBestFiringPlan(shooter,
                                                         shooter_state, target, target_state, game);
        righttwist_plan.twist = 1;
        shooter_state.setSecondaryFacing(correct_facing(orig_facing - 1));
        FiringPlan lefttwist_plan = guessBestFiringPlan(shooter, shooter_state,
                                                        target, target_state, game);
        lefttwist_plan.twist = -1;
        shooter_state.setSecondaryFacing(orig_facing);
        if ((notwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage())
                && (notwist_plan.getExpectedDamage() > lefttwist_plan
                .getExpectedDamage())) {
            return notwist_plan;
        }
        if (lefttwist_plan.getExpectedDamage() > righttwist_plan
                .getExpectedDamage()) {
            return lefttwist_plan;
        }
        return righttwist_plan;
    }

    /*
     * Skeleton for guessing the best air to ground firing plan. Currently this
     * code is working in basicpathranker FiringPlan
     * guessBestAirToGroundFiringPlan(Entity shooter,MovePath shooter_path,IGame
     * game) { ArrayList<Entity>
     * targets=getEnemiesUnderFlightPath(shooter_path,shooter,game); for(Entity
     * target:targets) { FiringPlan theplan=guessFullAirToGroundPlan(shooter,
     * target,new EntityState(target),shooter_path,game,true);
     *
     * }
     *
     *
     * }
     */

    /**
     * Gets all the entities that are potential targets (even if you can't
     * technically hit them)
     */
    ArrayList<Targetable> getTargetableEnemyEntities(Entity shooter, IGame game) {
        ArrayList<Targetable> ret = new ArrayList<Targetable>();
        for (Entity e : game.getEntitiesVector()) {
            if (e.getOwner().isEnemyOf(shooter.getOwner())
                    && (e.getPosition() != null) && !e.isOffBoard() && e.isTargetable()) {
                ret.add(e);
            }
        }
        ret.addAll(additionalTargets);
        return ret;
    }

    /**
     * This is it. Calculate the 'best' possible firing plan for this entity.
     * Overload this function if you think you can do better.
     */
    FiringPlan getBestFiringPlan(Entity shooter, IGame game) {
        FiringPlan bestplan = null;
        ArrayList<Targetable> enemies = getTargetableEnemyEntities(shooter,
                                                                   game);
        for (Targetable e : enemies) {
            FiringPlan plan = getBestFiringPlanWithTwists(shooter, e, game);
            if ((bestplan == null) || (plan.getUtility() > bestplan.getUtility())) {
                bestplan = plan;
            }
        }
        return bestplan;
    }

    public double getMaxDamageAtRange(Entity shooter, int range) {
        double ret = 0;
        for (Mounted mw : shooter.getWeaponList()) { // cycle through my weapons
            WeaponType wtype = (WeaponType) mw.getType();
            if (range < wtype.getLongRange()) {
                if (wtype.getDamage() > 0) {
                    ret += wtype.getDamage();
                }
            }
        }
        return ret;
    }

    /**
     * makes sure facing falls between 0 and 5 This function likely already
     * exists somewhere else
     */
    public static int correct_facing(int f) {
        while (f < 0) {
            f += 6;
        }
        if (f > 5) {
            f = f % 6;
        }
        return f;
    }

    /**
     * Makes sure ammo is loaded for each weapon
     */
    public void loadAmmo(Entity shooter, Targetable target) {
        if (shooter == null) {
            return;
        }

        // Loading ammo for all my weapons.
        Iterator<Mounted> weapons = shooter.getWeapons();
        while (weapons.hasNext()) {
            Mounted currentWeapon = weapons.next();
            WeaponType weaponType = (WeaponType) currentWeapon.getType();

            // Skip weapons that don't use ammo.
            if (AmmoType.T_NA == weaponType.getAmmoType()) {
                continue;
            }

            Mounted mountedAmmo = getPreferredAmmo(shooter, target, weaponType);
            // Log failures.
            if ((mountedAmmo != null) && !shooter.loadWeapon(currentWeapon, mountedAmmo)) {
                owner.log(getClass(), "loadAmmo(Entity, Targetable)", LogLevel.WARNING,
                          shooter.getDisplayName() + " tried to load " + currentWeapon.getName() + " with ammo " +
                                  mountedAmmo.getDesc() + " but failed somehow.");
            }
        }
    }

    /*
     * Here's a list of things that aren't technically units, but I want to be
     * able to target anyways. This is create with buildings and bridges and
     * mind
     */
    private List<Targetable> additionalTargets = new ArrayList<Targetable>();

    public List<Targetable> getAdditionalTargets() {
        return additionalTargets;
    }

    public void setAdditionalTargets(List<Targetable> targets) {
        additionalTargets = targets;
    }

    protected Mounted getClusterAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_CLUSTER == ammoType.getMunitionType()) {
                // MMLs have additional considerations.
                // There are no "cluster" missile munitions at this point in time.  Code is included in case
                // they are added to the game at some later date.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if (mmlLrm != null) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getPreferredAmmo(Entity shooter, Targetable target, WeaponType weaponType) {
        final String METHOD_NAME = "getPreferredAmmo(Entity, Targetable, WeaponType)";

        StringBuilder msg = new StringBuilder("Getting ammo for ").append(weaponType.getShortName()).append(" firing " +
                                                                                                                    "at ").append(target.getDisplayName());
        Entity targetEntity = null;
        Mounted preferredAmmo = null;

        try {
            boolean fireResistant = false;
            if (target instanceof Entity) {
                targetEntity = (Entity) target;
                int armorType = targetEntity.getArmorType(0);
                if (targetEntity instanceof Mech) {
                    targetEntity.getArmorType(1);
                }
                if (EquipmentType.T_ARMOR_BA_FIRE_RESIST == armorType
                        || EquipmentType.T_ARMOR_HEAT_DISSIPATING == armorType) {
                    fireResistant = true;
                }
            }

            // Find the ammo that is valid for this weapon.
            List<Mounted> ammo = shooter.getAmmo();
            List<Mounted> validAmmo = new ArrayList<Mounted>();
            for (Mounted a : ammo) {
                if (AmmoType.isAmmoValid(a, weaponType)) {
                    validAmmo.add(a);
                }
            }

            // If no valid ammo was found, return nothing.
            if (validAmmo.isEmpty()) {
                return preferredAmmo;
            }
            msg.append("\n\tFound ").append(validAmmo.size()).append(" units of valid ammo.");

            int range = shooter.getPosition().distance(target.getPosition());
            msg.append("\n\tRange to target is ").append(range);

            // AMS only uses 1 type of ammo.
            if (weaponType.hasFlag(WeaponType.F_AMS)) {
                return validAmmo.get(0);
            }

            // ATMs
            if (weaponType instanceof ATMWeapon) {
                return getAtmAmmo(validAmmo, range, new EntityState(target), fireResistant);
            }

            // Target is a building.
            if (target instanceof BuildingTarget) {
                msg.append("\n\tTarget is a building... ");
                preferredAmmo = getIncendiaryAmmo(validAmmo, weaponType, range);
                if (preferredAmmo != null) {
                    msg.append("Burn It Down!");
                    return preferredAmmo;
                }

                // Entity targets.
            } else if (targetEntity != null) {
                // Airborne targets
                if (targetEntity.isAirborne() || (targetEntity instanceof VTOL)) {
                    msg.append("\n\tTarget is airborne... ");
                    preferredAmmo = getAntiAirAmmo(validAmmo, weaponType, range);
                    if (preferredAmmo != null) {
                        msg.append("Shoot It Down!");
                        return preferredAmmo;
                    }
                }
                // Battle Armor, Tanks and Protos, oh my!
                if ((targetEntity instanceof BattleArmor)
                        || (targetEntity instanceof Tank)
                        || (targetEntity instanceof Protomech)) {
                    msg.append("\n\tTarget is BA/Proto/Tank... ");
                    preferredAmmo = getAntiVeeAmmo(validAmmo, weaponType, range, fireResistant);
                    if (preferredAmmo != null) {
                        msg.append("We have ways of dealing with that.");
                        return preferredAmmo;
                    }
                }
                // PBI
                if (targetEntity instanceof Infantry) {
                    msg.append("\n\tTarget is infantry... ");
                    preferredAmmo = getAntiInfantryAmmo(validAmmo, weaponType, range);
                    if (preferredAmmo != null) {
                        msg.append("They squish nicely.");
                        return preferredAmmo;
                    }
                }
                // On his last legs
                if (targetEntity.getDamageLevel() >= Entity.DMG_HEAVY) {
                    msg.append("\n\tTarget is heavily damaged... ");
                    preferredAmmo = getClusterAmmo(validAmmo, weaponType, range);
                    if (preferredAmmo != null) {
                        msg.append("Let's find a soft spot.");
                        return preferredAmmo;
                    }
                }
                // He's running hot.
                if (targetEntity.getHeat() >= 9 && !fireResistant) {
                    msg.append("\n\tTarget is at ").append(targetEntity.getHeat()).append(" heat... ");
                    preferredAmmo = getHeatAmmo(validAmmo, weaponType, range);
                    if (preferredAmmo != null) {
                        msg.append("Let's heat him up more.");
                        return preferredAmmo;
                    }
                }
                // Everything else.
                msg.append("\n\tTarget is a hard target... ");
                preferredAmmo = getHardTargetAmmo(validAmmo, weaponType, range);
                if (preferredAmmo != null) {
                    msg.append("Fill him with holes!");
                    return preferredAmmo;
                }
            }

            // If we've gotten this far, no specialized ammo has been loaded
            if (weaponType instanceof MMLWeapon) {
                msg.append("\n\tLoading MML Ammo.");
                preferredAmmo = getGeneralMmlAmmo(validAmmo, range);
            } else {
                msg.append("\n\tLoading first available ammo.");
                preferredAmmo = validAmmo.get(0);
            }
            return preferredAmmo;
        } finally {
            msg.append("\n\tReturning: ").append(preferredAmmo == null ? "null" : preferredAmmo.getDesc());
            owner.log(getClass(), METHOD_NAME, LogLevel.DEBUG, msg.toString());
        }
    }

    protected Mounted getGeneralMmlAmmo(List<Mounted> ammoList, int range) {
        Mounted returnAmmo = null;

        // Get the LRM and SRM bins if we have them.
        Mounted mmlSrm = null;
        Mounted mmlLrm = null;
        for (Mounted ammo : ammoList) {
            AmmoType type = (AmmoType) ammo.getType();
            if ((mmlLrm == null) && type.hasFlag(AmmoType.F_MML_LRM)) {
                mmlLrm = ammo;
            } else if (mmlSrm == null) {
                mmlSrm = ammo;
            } else if ((mmlSrm != null) && (mmlLrm != null)) {
                break;
            }
        }

        // Out of SRM range.
        if (range > 9) {
            returnAmmo = mmlLrm;

            // LRMs have better chance to hit if we have them.
        } else if (range > 5) {
            returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);

            // If we only have LRMs left.
        } else if (mmlSrm == null) {
            returnAmmo = mmlLrm;

            // Left with SRMS.
        } else {
            returnAmmo = mmlSrm;
        }
        return returnAmmo;
    }

    protected Mounted getAtmAmmo(List<Mounted> ammoList, int range, EntityState target, boolean fireResistant) {
        Mounted returnAmmo = null;

        // Get the Hi-Ex, Ex-Range and Standard ammo bins if we have them.
        Mounted heAmmo = null;
        Mounted erAmmo = null;
        Mounted stAmmo = null;
        Mounted infernoAmmo = null;
        for (Mounted ammo : ammoList) {
            AmmoType type = (AmmoType) ammo.getType();
            if ((heAmmo == null) && (AmmoType.M_HIGH_EXPLOSIVE == type.getMunitionType())) {
                heAmmo = ammo;
            } else if ((erAmmo == null) && (AmmoType.M_EXTENDED_RANGE == type.getMunitionType())) {
                erAmmo = ammo;
            } else if ((stAmmo == null) && (AmmoType.M_STANDARD == type.getMunitionType())) {
                stAmmo = ammo;
            } else if ((infernoAmmo == null) && (AmmoType.M_IATM_IIW == type.getMunitionType())) {
                infernoAmmo = ammo;
            } else if ((heAmmo != null) && (erAmmo != null) && (stAmmo != null) && (infernoAmmo != null)) {
                break;
            }
        }

        // Beyond 15 hexes is ER Ammo only range.
        if (range > 15) {
            returnAmmo = erAmmo;
            // ER Ammo has a better chance to hit past 10 hexes.
        } else if (range > 10) {
            returnAmmo = (erAmmo == null ? stAmmo : erAmmo);
            // At 7-10 hexes, go with Standard, then ER then HE due to hit odds.
        } else if (range > 6) {
            if (stAmmo != null) {
                returnAmmo = stAmmo;
            } else if (erAmmo != null) {
                returnAmmo = erAmmo;
            } else {
                returnAmmo = heAmmo;
            }
            // Six hexes is at min for ER, and medium for both ST & HE.
        } else if (range == 6) {
            if (heAmmo != null) {
                returnAmmo = heAmmo;
            } else if (stAmmo != null) {
                returnAmmo = stAmmo;
            } else {
                returnAmmo = erAmmo;
            }
            // 4-5 hexes is medium for HE, short for ST and well within min for ER.
        } else if (range > 3) {
            if (stAmmo != null) {
                returnAmmo = stAmmo;
            } else if (heAmmo != null) {
                returnAmmo = heAmmo;
            } else {
                returnAmmo = erAmmo;
            }
            // Short range for HE.
        } else {
            if (heAmmo != null) {
                returnAmmo = heAmmo;
            } else if (stAmmo != null) {
                returnAmmo = stAmmo;
            } else {
                returnAmmo = erAmmo;
            }
        }

        if ((returnAmmo == stAmmo) && (infernoAmmo != null)
                && ((target.getHeat() >= 9) || target.isBuilding())
                && !fireResistant) {
            returnAmmo = infernoAmmo;
        }

        return returnAmmo;
    }

    protected Mounted getAntiVeeAmmo(List<Mounted> ammoList, WeaponType weaponType, int range, boolean fireResistant) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_CLUSTER == ammoType.getMunitionType()
                    || (AmmoType.M_INFERNO == ammoType.getMunitionType() && !fireResistant)
                    || (AmmoType.M_INFERNO_IV == ammoType.getMunitionType() && !fireResistant)) {

                // MMLs have additional considerations.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if (mmlLrm != null) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getAntiInfantryAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_FLECHETTE == ammoType.getMunitionType()
                    || AmmoType.M_FRAGMENTATION == ammoType.getMunitionType()
                    || AmmoType.M_CLUSTER == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO_IV == ammoType.getMunitionType()) {

                // MMLs have additional considerations.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if ((mmlLrm != null) && (mmlSrm != null)) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getHeatAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_INFERNO == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO_IV == ammoType.getMunitionType()) {

                // MMLs have additional considerations.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if (mmlLrm != null) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getIncendiaryAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_INCENDIARY == ammoType.getMunitionType()
                    || AmmoType.M_INCENDIARY_LRM == ammoType.getMunitionType()
                    || AmmoType.M_INCENDIARY_AC == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO_IV == ammoType.getMunitionType()) {

                // MMLs have additional considerations.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if (mmlLrm != null) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getHardTargetAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_CLUSTER == ammoType.getMunitionType()
                    || AmmoType.M_ANTI_FLAME_FOAM == ammoType.getMunitionType()
                    || AmmoType.M_CHAFF == ammoType.getMunitionType()
                    || AmmoType.M_COOLANT == ammoType.getMunitionType()
                    || AmmoType.M_ECM == ammoType.getMunitionType()
                    || AmmoType.M_FASCAM == ammoType.getMunitionType()
                    || AmmoType.M_FLAK == ammoType.getMunitionType()
                    || AmmoType.M_FLARE == ammoType.getMunitionType()
                    || AmmoType.M_FLECHETTE == ammoType.getMunitionType()
                    || AmmoType.M_FRAGMENTATION == ammoType.getMunitionType()
                    || AmmoType.M_HAYWIRE == ammoType.getMunitionType()
                    || AmmoType.M_INCENDIARY == ammoType.getMunitionType()
                    || AmmoType.M_INCENDIARY_AC == ammoType.getMunitionType()
                    || AmmoType.M_INCENDIARY_LRM == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO == ammoType.getMunitionType()
                    || AmmoType.M_INFERNO_IV == ammoType.getMunitionType()
                    || AmmoType.M_LASER_INHIB == ammoType.getMunitionType()
                    || AmmoType.M_OIL_SLICK == ammoType.getMunitionType()
                    || AmmoType.M_NEMESIS == ammoType.getMunitionType()
                    || AmmoType.M_PAINT_OBSCURANT == ammoType.getMunitionType()
                    || AmmoType.M_SMOKE == ammoType.getMunitionType()
                    || AmmoType.M_SMOKE_WARHEAD == ammoType.getMunitionType()
                    || AmmoType.M_SMOKEGRENADE == ammoType.getMunitionType()
                    || AmmoType.M_THUNDER == ammoType.getMunitionType()
                    || AmmoType.M_THUNDER_ACTIVE == ammoType.getMunitionType()
                    || AmmoType.M_THUNDER_AUGMENTED == ammoType.getMunitionType()
                    || AmmoType.M_THUNDER_INFERNO == ammoType.getMunitionType()
                    || AmmoType.M_THUNDER_VIBRABOMB == ammoType.getMunitionType()
                    || AmmoType.M_TORPEDO == ammoType.getMunitionType()
                    || AmmoType.M_VIBRABOMB_IV == ammoType.getMunitionType()
                    || AmmoType.M_WATER == ammoType.getMunitionType()
                    || AmmoType.M_ANTI_TSM == ammoType.getMunitionType()
                    || AmmoType.M_CORROSIVE == ammoType.getMunitionType()) {
                continue;
            }
            // MMLs have additional considerations.
            if (!(weaponType instanceof MMLWeapon)) {
                returnAmmo = ammo;
                break;
            }
            if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                mmlLrm = ammo;
            } else if (mmlSrm == null) {
                mmlSrm = ammo;
            } else if ((mmlLrm != null) && (mmlSrm != null)) {
                break;
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }

    protected Mounted getAntiAirAmmo(List<Mounted> ammoList, WeaponType weaponType, int range) {
        Mounted returnAmmo = null;
        Mounted mmlLrm = null;
        Mounted mmlSrm = null;

        for (Mounted ammo : ammoList) {
            AmmoType ammoType = (AmmoType) ammo.getType();
            if (AmmoType.M_CLUSTER == ammoType.getMunitionType()
                    || AmmoType.M_FLAK == ammoType.getMunitionType()) {

                // MMLs have additional considerations.
                // There are no "flak" or "cluster" missile munitions at this point in time.  Code is included in case
                // they are added to the game at some later date.
                if (!(weaponType instanceof MMLWeapon)) {
                    returnAmmo = ammo;
                    break;
                }
                if ((mmlLrm == null) && ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                    mmlLrm = ammo;
                } else if (mmlSrm == null) {
                    mmlSrm = ammo;
                } else if (mmlLrm != null) {
                    break;
                }
            }
        }

        // MML ammo depends on range.
        if (weaponType instanceof MMLWeapon) {
            if (range > 9) { // Out of SRM range
                returnAmmo = mmlLrm;
            } else if (range > 6) { // SRM long range.
                returnAmmo = (mmlLrm == null ? mmlSrm : mmlLrm);
            } else {
                returnAmmo = (mmlSrm == null ? mmlLrm : mmlSrm);
            }
        }

        return returnAmmo;
    }
}
