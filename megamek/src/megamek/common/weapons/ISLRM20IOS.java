/**
 * MegaMek - Copyright (C) 2005 Ben Mazur (bmazur@sev.org)
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 */
package megamek.common.weapons;

import megamek.common.TechConstants;

/**
 * @author Sebastian Brocks
 */
public class ISLRM20IOS extends LRMWeapon {

    /**
     *
     */
    private static final long serialVersionUID = 3960681625679721032L;

    /**
     *
     */
    public ISLRM20IOS() {
        super();
        techLevel.put(3071, TechConstants.T_IS_ADVANCED);
        name = "LRM 20 (I-OS)";
        setInternalName(name);
        addLookupName("IS IOS LRM-20");
        addLookupName("ISLRM20 (IOS)");
        addLookupName("IS LRM 20 (IOS)");
        heat = 6;
        rackSize = 20;
        minimumRange = 6;
        tonnage = 9.5f;
        criticals = 5;
        bv = 36;
        flags = flags.or(F_ONESHOT);
        cost = 200000;
        shortAV = 12;
        medAV = 12;
        longAV = 12;
        maxRange = RANGE_LONG;
        techRating = RATING_B;
        availRating = new int[] { RATING_X, RATING_X, RATING_F };
        introDate = 3056;
        techLevel.put(3056, techLevel.get(3071));
        techLevel.put(3081, TechConstants.T_IS_TW_NON_BOX);
    }
}
