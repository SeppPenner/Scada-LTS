package org.scada_lts.mango.service;

import br.org.scadabr.api.exception.DAOException;
import br.org.scadabr.vo.permission.ViewAccess;
import br.org.scadabr.vo.permission.WatchListAccess;
import br.org.scadabr.vo.usersProfiles.UsersProfileVO;
import com.serotonin.mango.Common;
import com.serotonin.mango.vo.User;
import com.serotonin.mango.vo.permission.DataPointAccess;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scada_lts.dao.DAO;
import org.scada_lts.dao.UserDAO;
import org.scada_lts.dao.UsersProfileDAO;
import org.scada_lts.permissions.service.*;
import org.scada_lts.permissions.service.util.PermissionsUtils;
import org.scada_lts.serorepl.utils.StringUtils;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UsersProfileService {

    private static final Log LOG = LogFactory.getLog(UsersProfileService.class);
    private static final String LIST_SIZE_IS_GREATER_THAN_1 = "The user has more than one profile assigned. \nuserId: {0},\nprofiles: {1}\n";

    private static final Set<UsersProfileVO> currentProfileList = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicInteger lock = new AtomicInteger();

    private final UsersProfileDAO usersProfileDAO;
    private final DAO dao;
    private final UserDAO userDAO;
    private final PermissionsService<WatchListAccess, UsersProfileVO> watchListPermissionsService;
    private final PermissionsService<DataPointAccess, UsersProfileVO> dataPointPermissionsService;
    private final PermissionsService<Integer, UsersProfileVO> dataSourcePermissionsService;
    private final PermissionsService<ViewAccess, UsersProfileVO> viewPermissionsService;

    public UsersProfileService() {
        this.usersProfileDAO = new UsersProfileDAO();
        this.dao = DAO.getInstance();
        this.userDAO = new UserDAO();
        this.watchListPermissionsService = new WatchListProfilePermissionsService();
        this.dataPointPermissionsService = new DataPointProfilePermissionsService();
        this.dataSourcePermissionsService = new DataSourceProfilePermissionsService();
        this.viewPermissionsService = new ViewProfilePermissionsService();
    }

    public UsersProfileService(UsersProfileDAO usersProfileDAO, DAO dao, UserDAO userDAO,
                               PermissionsService<WatchListAccess, UsersProfileVO> watchListPermissionsService,
                               PermissionsService<DataPointAccess, UsersProfileVO> dataPointPermissionsService,
                               PermissionsService<Integer, UsersProfileVO> dataSourcePermissionsService,
                               PermissionsService<ViewAccess, UsersProfileVO> viewPermissionsService) {
        this.usersProfileDAO = usersProfileDAO;
        this.dao = dao;
        this.userDAO = userDAO;
        this.watchListPermissionsService = watchListPermissionsService;
        this.dataPointPermissionsService = dataPointPermissionsService;
        this.dataSourcePermissionsService = dataSourcePermissionsService;
        this.viewPermissionsService = viewPermissionsService;
    }

    public List<UsersProfileVO> getUsersProfiles() {
        return getUsersProfiles(Comparator.comparing(UsersProfileVO::getName));
    }

    public List<UsersProfileVO> getUsersProfiles(Comparator<UsersProfileVO> comparator) {
        if(isEmpty()) {
            if(lock.getAndDecrement() == 0) {
                try {
                    getProfiles(Integer.MAX_VALUE)
                            .forEach(a -> {
                                populateUserProfilePermissions(a);
                                add(a);
                            });
                } finally {
                    lock.set(0);
                }
            }
        }
        return stream()
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    public String generateUniqueXid() {
        return dao.generateUniqueXid(UsersProfileVO.XID_PREFIX, "usersProfiles");
    }

    public UsersProfileVO getUserProfileByName(String name) {
        return getUsersProfile(a -> !StringUtils.isEmpty(name) && name.equals(a.getName()));
    }

    public UsersProfileVO getUserProfileById(int id) {
        return getUsersProfile(a -> id != Common.NEW_ID && a.getId() == id);
    }

    public UsersProfileVO getUserProfileByXid(String xid) {
        return getUsersProfile(a -> !StringUtils.isEmpty(xid) && xid.equals(a.getXid()));
    }

    public void saveUsersProfile(UsersProfileVO profile) throws DAOException {
        if (profileExistsWithThatName(profile)
                && profile.getId() == Common.NEW_ID) {
            throw new DAOException();
        }

        saveUsersProfileWithoutNameConstraint(profile);
    }

    private boolean profileExistsWithThatName(UsersProfileVO profile) {
        return getUsersProfile(a -> !StringUtils.isEmpty(profile.getName()) && profile.getName().equals(a.getName())) != null;
    }

    public void saveUsersProfileWithoutNameConstraint(UsersProfileVO profile)
            throws DAOException {
        if (profile.getName() == null
                || profile.getName().replaceAll("\\s+", "").isEmpty()) {
            throw new DAOException();
        }

        if (profile.getXid() == null) {
            profile.setXid(generateUniqueXid());
        }

        if (profile.getId() == Common.NEW_ID) {
            insertProfile(profile);
        } else {
            updateProfile(profile);
        }
    }

    public void updatePermissions() {
        updateViewPermissions();
        updateDataPointPermissions();
        updateWatchlistPermissions();
        updateDataSourcePermissions();
    }

    public void updateWatchlistPermissions() {
        forEach(this::populateWatchlists);
    }

    public void updateViewPermissions() {
        forEach(this::populateViews);
    }

    public void updateDataPointPermissions() {
        forEach(this::populateDatapoints);
    }

    public void updateDataSourcePermissions() {
        forEach(this::populateDataSources);
    }

    public void updateProfile(UsersProfileVO profile) {
        setProfileName(profile.getName(), profile);
        saveRelationalData(profile);
        add(profile);
    }

    public void updateUsersProfile(User user, UsersProfileVO profile) {
        if (user != null) {
            getProfileByUser(user).ifPresent(a -> removeUserProfile(user));
            createUserProfile(user, profile);
        }
    }

    public UsersProfileVO getUserProfileByUserId(int userid) {
        return getProfileByUserId(userid).orElse(null);
    }

    public void grantUserAdminProfile(User user) {
        removeUserProfile(user);
        user.resetUserProfile();
    }

    public void resetUserProfile(User user) {
        removeUserProfile(user);
        user.resetUserProfile();
    }

    public boolean userProfileExists(String xid) {
        UsersProfileVO profile = getUserProfileByXid(xid);
        return profile != null;
    }

    public void deleteUserProfile(final int usersProfileId) {
        // Get Users from Profile
        List<Integer> usersIds = getUsersByProfileId(usersProfileId);

        // Reset user profile
        for (Integer userId : usersIds) {
            this.resetUserProfile(userDAO.getUser(userId));
        }
        removeProfile(usersProfileId);
        removeIf(a -> a.getId() == usersProfileId);
    }

    public Optional<UsersProfileVO> getProfileByUser(User user) {
        return getProfileByUserId(user.getId());
    }

    public Optional<UsersProfileVO> getProfileByUserId(int userId) {
        List<UsersProfileVO> profiles = usersProfileDAO.selectUserProfileByUserId(userId);
        if(profiles.isEmpty())
            return Optional.empty();
        if(profiles.size() > 1) {
            LOG.warn(MessageFormat.format(LIST_SIZE_IS_GREATER_THAN_1, userId, profiles));
        }
        return profiles.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(UsersProfileVO::getId));
    }

    private static UsersProfileVO getUsersProfile(Predicate<UsersProfileVO> filter) {
        return stream()
                .peek(a -> LOG.debug(a.getName() + ' ' + a.getXid() + ' ' + a.getId()))
                .filter(filter)
                .findFirst()
                .orElseGet(() -> {
                    LOG.warn("Profile not Found!");
                    return null;
                });
    }

    private static boolean add(UsersProfileVO profileVO) {
        return currentProfileList.add(profileVO);
    }

    private static boolean removeIf(Predicate<? super UsersProfileVO> filter) {
        return currentProfileList.removeIf(filter);
    }

    private static Stream<UsersProfileVO> stream() {
        return currentProfileList.stream();
    }

    private static void forEach(Consumer<? super UsersProfileVO> action) {
        currentProfileList.forEach(action);
    }

    private static boolean isEmpty() {
        return currentProfileList.isEmpty();
    }

    private List<UsersProfileVO> getProfiles(int limit) {
        return usersProfileDAO.selectProfiles(0, limit);
    }

    private void populateUserProfilePermissions(UsersProfileVO profile) {
        if (profile == null) {
            return;
        }

        LOG.debug("populateDataSources");
        populateDataSources(profile);
        LOG.debug("populateDatapoints");
        populateDatapoints(profile);
        LOG.debug("populateWatchlists");
        populateWatchlists(profile);
        LOG.debug("populateViews");
        populateViews(profile);
        LOG.debug("populateUsers");
        populateUsers(profile);
        LOG.debug("end");
    }

    private void populateUsers(UsersProfileVO profile) {
        profile.defineUsers(getUsersByProfile(profile));
    }

    private void populateWatchlists(UsersProfileVO profile) {
        profile.setWatchlistPermissions(watchListPermissionsService.getPermissions(profile));
    }

    private void populateDatapoints(UsersProfileVO profile) {
        profile.setDataPointPermissions(dataPointPermissionsService.getPermissions(profile));
    }

    private void populateDataSources(UsersProfileVO profile) {
        profile.setDataSourcePermissions(dataSourcePermissionsService.getPermissions(profile));
    }

    private void populateViews(UsersProfileVO profile) {
        profile.setViewPermissions(viewPermissionsService.getPermissions(profile));
    }

    private void insertProfile(UsersProfileVO profile) {
        profile.setId(createProfile(profile.getXid(), profile.getName()));
        saveRelationalData(profile);
        add(profile);
    }

    private void removeProfile(int profileId) {
        usersProfileDAO.deleteProfile(profileId);
    }

    private void createUserProfile(User user, UsersProfileVO profile) {
        usersProfileDAO.insertUserProfile(user.getId(), profile.getId());
    }

    private int createProfile(String profileXid, String profileName) {
        return usersProfileDAO.insertProfile(profileXid, profileName);
    }

    private void saveRelationalData(final UsersProfileVO usersProfile) {
        PermissionsUtils.updateDataSourcePermissions(usersProfile, dataSourcePermissionsService);
        PermissionsUtils.updateDataPointPermissions(usersProfile,dataPointPermissionsService);
        PermissionsUtils.updateViewPermissions(usersProfile, viewPermissionsService);
        PermissionsUtils.updateWatchListPermissions(usersProfile, watchListPermissionsService);
    }

    private List<Integer> getUsersByProfile(UsersProfileVO profile) {
        return getUsersByProfileId(profile.getId());
    }

    private List<Integer> getUsersByProfileId(int usersProfileId) {
        return usersProfileDAO.selectUsersByProfileId(usersProfileId);
    }

    private void setProfileName(String name, UsersProfileVO profile) {
        usersProfileDAO.updateProfileName(name, profile.getId());
    }

    private void removeUserProfile(User user) {
        usersProfileDAO.deleteUserProfileByUserId(user.getId());
    }
}
