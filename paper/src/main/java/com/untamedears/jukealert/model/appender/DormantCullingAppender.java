package com.untamedears.jukealert.model.appender;

import com.untamedears.jukealert.JukeAlert;
import com.untamedears.jukealert.model.Snitch;
import com.untamedears.jukealert.model.actions.abstr.LoggablePlayerAction;
import com.untamedears.jukealert.model.actions.abstr.SnitchAction;
import com.untamedears.jukealert.model.actions.internal.DestroySnitchAction;
import com.untamedears.jukealert.model.actions.internal.DestroySnitchAction.Cause;
import com.untamedears.jukealert.model.appender.config.DormantCullingConfig;
import com.untamedears.jukealert.util.JukeAlertPermissionHandler;
import javax.annotation.Nonnull;
import org.bukkit.configuration.ConfigurationSection;
import vg.civcraft.mc.civmodcore.utilities.BukkitComparators;
import vg.civcraft.mc.civmodcore.utilities.CivLogger;
import vg.civcraft.mc.civmodcore.utilities.progress.ProgressTrackable;

public class DormantCullingAppender
		extends ConfigurableSnitchAppender<DormantCullingConfig>
		implements ProgressTrackable {
	
	public static final String ID = "dormantcull";
	private static final CivLogger LOGGER = CivLogger.getLogger(DormantCullingAppender.class);

	private long lastRefresh;
	private long nextUpdate;

	private enum ActivityStatus { ACTIVE, DORMANT, CULLED }
	private ActivityStatus databaseKnownStatus;

	public DormantCullingAppender(final Snitch snitch,
								  final ConfigurationSection config) {
		super(snitch, config);
		this.lastRefresh = snitch.getId() == -1 ? System.currentTimeMillis() :
				JukeAlert.getInstance().getDAO().getRefreshTimer(snitch.getId());
	}

	/**
	 * @return Returns the UNIX timestamp of when this snitch was last refreshed.
	 */
	public long getLastRefresh() {
		return this.lastRefresh;
	}

	/**
	 * Updates this snitch's last refresh time.
	 */
	public void updateLastRefresh() {
		this.lastRefresh = System.currentTimeMillis();
		updateState();
		getSnitch().setDirty();
	}

	/** {@inheritDoc} */
	@Override
	public long getNextUpdate() {
		return this.nextUpdate;
	}

	/**
	 * @return Returns this snitch's expected activity status.
	 */
	@Nonnull
	public ActivityStatus getActivityStatus() {
		final long timeSinceLastRefresh = getTimeSinceLastRefresh();
		if (timeSinceLastRefresh >= this.config.getTotalLifeTime()) {
			return ActivityStatus.CULLED;
		}
		if (timeSinceLastRefresh >= this.config.getLifetime()) {
			return ActivityStatus.DORMANT;
		}
		return ActivityStatus.ACTIVE;
	}

	/**
	 * @return Returns how many milliseconds it has been since this snitch was last refreshed.
	 */
	public long getTimeSinceLastRefresh() {
		return System.currentTimeMillis() - getLastRefresh();
	}

	/**
	 * @return Returns how many milliseconds until the snitch goes dormant.
	 */
	public long getTimeUntilDormant() {
		return this.config.getLifetime() - getTimeSinceLastRefresh();
	}

	/**
	 * @return Returns how many milliseconds until the snitch culls.
	 */
	public long getTimeUntilCulling() {
		return this.config.getTotalLifeTime() - getTimeSinceLastRefresh();
	}

	@Override
	public void updateInternalProgressTime(final long update) {
		this.nextUpdate = update;
	}

	private long calcFutureUpdate() {
		switch (getActivityStatus()) {
			case CULLED:
				return Long.MAX_VALUE;
			case DORMANT:
				return getLastRefresh() + this.config.getTotalLifeTime();
			default:
			case ACTIVE:
				return getLastRefresh() + this.config.getLifetime();
		}
	}

	/** {@inheritDoc} */
	@Override
	public void postSetup() {
		if (this.lastRefresh == -1) {
			// no data in db due to recent config change, let's use the current time and mark it for saving later
			updateLastRefresh();
		}
		this.databaseKnownStatus = getActivityStatus();
		updateInternalProgressTime(calcFutureUpdate());
		JukeAlert.getInstance().getSnitchCullManager().addCulling(this);
		updateState();
	}

	/** {@inheritDoc} */
	@Override
	public boolean runWhenSnitchInactive() {
		return true;
	}

	/** {@inheritDoc} */
	@Override
	public void acceptAction(final SnitchAction action) {
		if (action.isLifeCycleEvent()) {
			if (action instanceof DestroySnitchAction) {
				JukeAlert.getInstance().getSnitchCullManager().removeCulling(this);
			}
			return;
		}
		if (!action.hasPlayer()) {
			return;
		}
		final LoggablePlayerAction playerAction = (LoggablePlayerAction) action;
		if (getSnitch().hasPermission(playerAction.getPlayer(), JukeAlertPermissionHandler.getListSnitches())) {
			updateLastRefresh();
		}
	}
	
	/**
	 * @return Returns whether the snitch is currently active, meaning neither dormant nor culled.
	 */
	public boolean isActive() {
		final long timeSinceLastRefresh = getTimeSinceLastRefresh();
		if (timeSinceLastRefresh >= this.config.getTotalLifeTime()) {
			return false;
		}
		return timeSinceLastRefresh < this.config.getLifetime();
	}

	/**
	 * @return Returns whether the snitch is currently dormant, meaning no longer active but not yet culled.
	 */
	public boolean isDormant() {
		final long timeSinceLastRefresh = getTimeSinceLastRefresh();
		if (timeSinceLastRefresh >= this.config.getTotalLifeTime()) {
			return false;
		}
		return timeSinceLastRefresh >= this.config.getLifetime();
	}

	/** {@inheritDoc} */
	@Override
	public void persist() {
		if (getSnitch().getId() != -1) {
			JukeAlert.getInstance().getDAO().setRefreshTimer(getSnitch().getId(), getLastRefresh());
		}
	}

	/** {@inheritDoc} */
	@Override
	public void updateState() {
		final ActivityStatus currentStatus = getActivityStatus();
		// Culled
		if (currentStatus == ActivityStatus.CULLED && this.databaseKnownStatus != ActivityStatus.CULLED) {
			this.databaseKnownStatus = ActivityStatus.CULLED;
			getSnitch().destroy(null, Cause.CULL);
			updateInternalProgressTime(Long.MAX_VALUE);
			LOGGER.info("Culling snitch [" + getSnitch() + "] for exceeding life timer");
		}
		// Dormant
		else if (currentStatus == ActivityStatus.DORMANT && this.databaseKnownStatus != ActivityStatus.DORMANT) {
			this.databaseKnownStatus = ActivityStatus.DORMANT;
			updateInternalProgressTime(System.currentTimeMillis() + this.config.getDormantLifeTime());
			getSnitch().setActiveStatus(false);
			LOGGER.info("Deactivating snitch [" + getSnitch() + "] for exceeding dormant timer");
		}
		// Active
		else if (this.databaseKnownStatus != ActivityStatus.ACTIVE) {
			this.databaseKnownStatus = ActivityStatus.ACTIVE;
			getSnitch().setActiveStatus(true);
			JukeAlert.getInstance().getSnitchCullManager().updateCulling(this, calcFutureUpdate());
			LOGGER.info("Re-activating snitch [" + getSnitch() + "]");
		}
	}

	/** {@inheritDoc} */
	@Override
	public Class<DormantCullingConfig> getConfigClass() {
		return DormantCullingConfig.class;
	}

	/** {@inheritDoc} */
	@Override
	public int compareTo(@Nonnull final ProgressTrackable other) {
		return BukkitComparators.getLocation().compare(
				((AbstractSnitchAppender) other).getSnitch().getLocation(),
				getSnitch().getLocation());
	}

}
