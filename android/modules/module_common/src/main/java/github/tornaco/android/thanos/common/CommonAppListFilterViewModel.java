package github.tornaco.android.thanos.common;

import android.app.Application;
import android.app.usage.UsageStats;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.databinding.ObservableArrayList;
import androidx.databinding.ObservableBoolean;
import androidx.databinding.ObservableField;
import androidx.lifecycle.AndroidViewModel;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import github.tornaco.android.thanos.common.sort.AppSort;
import github.tornaco.android.thanos.core.app.ThanosManager;
import github.tornaco.android.thanos.core.pm.PackageSet;
import github.tornaco.android.thanos.core.pm.PrebuiltPkgSetsKt;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;
import rx2.android.schedulers.AndroidSchedulers;
import util.CollectionUtils;
import util.ObjectsUtils;

public class CommonAppListFilterViewModel extends AndroidViewModel {
    private static final String PREF_KEY_DEF_CATEGORY_ID_PREFIX = "pref.default.app.category.id_";

    public static final CategoryIndex DEFAULT_CATEGORY_INDEX = CategoryIndex.from(PrebuiltPkgSetsKt.PREBUILT_PACKAGE_SET_ID_3RD);

    private final ObservableBoolean isDataLoading = new ObservableBoolean(false);
    protected final List<Disposable> disposables = new ArrayList<>();

    protected final ObservableArrayList<AppListModel> listModels = new ObservableArrayList<>();

    private final ObservableField<CategoryIndex> categoryIndex = new ObservableField<>(DEFAULT_CATEGORY_INDEX);

    private final ObservableBoolean sortReverse = new ObservableBoolean(false);
    private final ObservableField<AppSort> currentSort = new ObservableField<>(AppSort.Default);

    private final ObservableField<String> queryText = new ObservableField<>("");
    private final AppLabelSearchFilter appLabelSearchFilter = new AppLabelSearchFilter();

    private final List<AppListModel> cachedFullAppList = new ArrayList<>();

    private ListModelLoader appListLoader;

    private String featureId;

    public CommonAppListFilterViewModel(@NonNull Application application) {
        super(application);
        registerEventReceivers();
    }

    public void start() {
        loadModels(false);
    }

    public void bindFeatureId(String featureId) {
        this.featureId = featureId;
        String preferredPkgSetId = PreferenceManager.getDefaultSharedPreferences(getApplication())
                .getString(PREF_KEY_DEF_CATEGORY_ID_PREFIX + featureId, DEFAULT_CATEGORY_INDEX.pkgSetId);
        // Check if valid
        ThanosManager thanox = ThanosManager.from(getApplication());
        if (thanox.isServiceInstalled()
                && thanox.getPkgManager().getPackageSetById(preferredPkgSetId, false) != null) {
            categoryIndex.set(CategoryIndex.from(preferredPkgSetId));
        } else {
            categoryIndex.set(DEFAULT_CATEGORY_INDEX);
        }
    }

    private List<AppListModel> requestLoadOrCachedAppList(boolean preferCache) {
        if (!preferCache) {
            List<AppListModel> latestAppList = appListLoader.load(Objects.requireNonNull(categoryIndex.get()));
            cachedFullAppList.clear();
            cachedFullAppList.addAll(latestAppList);
            return latestAppList;
        }
        // Check if cache exists.
        if (cachedFullAppList.isEmpty()) {
            cachedFullAppList.addAll(appListLoader.load(Objects.requireNonNull(categoryIndex.get())));
        }
        return cachedFullAppList;
    }

    private void loadModels(boolean preferCache) {
        XLog.v("loadModels, preferCache: %s", preferCache);
        isDataLoading.set(true);
        disposables.add(Single
                .create(new SingleOnSubscribe<List<AppListModel>>() {
                    @Override
                    public void subscribe(SingleEmitter<List<AppListModel>> emitter) throws Exception {
                        List<AppListModel> res = requestLoadOrCachedAppList(preferCache);

                        AppSort sort = currentSort.get();
                        if (sort != null) {
                            // inflate usage stats if necessary.
                            if (sort.relyOnUsageStats()) {
                                inflateAppUsageStats(res);
                            }

                            AppSort.AppSorterProvider appSorterProvider = sort.provider;
                            if (appSorterProvider != null) {
                                res.sort(appSorterProvider.comparator(getApplication()));
                                if (sortReverse.get()) {
                                    Collections.reverse(res);
                                }
                            }
                        }

                        emitter.onSuccess(res);
                    }
                })
                .flatMapObservable((Function<List<AppListModel>, ObservableSource<AppListModel>>) Observable::fromIterable)
                .filter(new Predicate<AppListModel>() {
                    @Override
                    public boolean test(AppListModel listModel) throws Exception {
                        String query = queryText.get();
                        return TextUtils.isEmpty(query)
                                // Match package name.
                                || (query != null && query.length() > 2 && listModel.appInfo.getPkgName().contains(query))
                                || appLabelSearchFilter.matches(query, listModel.appInfo.getAppLabel());
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(new Consumer<Disposable>() {
                    @Override
                    public void accept(Disposable disposable) throws Exception {
                        listModels.clear();
                    }
                })
                .doOnDispose(new Action() {
                    @Override
                    public void run() throws Exception {
                        XLog.d("loadModels on dispose");
                        isDataLoading.set(false);
                    }
                })
                .subscribe(new Consumer<AppListModel>() {
                    @Override
                    public void accept(AppListModel model) throws Exception {
                        // Append info from the sorter.
                        AppSort sort = currentSort.get();
                        if (sort != null) {
                            AppSort.AppSorterProvider appSorterProvider = sort.provider;
                            if (appSorterProvider != null) {
                                String appSortDescription = appSorterProvider.getAppSortDescription(getApplication(), model);
                                if (!TextUtils.isEmpty(appSortDescription)) {
                                    model.description = ((model.description == null ? "" : model.description) + "\n" + appSortDescription).trim();
                                }
                            }
                        }
                        listModels.add(model);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        XLog.e(Log.getStackTraceString(throwable));
                        isDataLoading.set(false);
                    }
                }, new Action() {
                    @Override
                    public void run() throws Exception {
                        XLog.d("loadModels on complete");
                        isDataLoading.set(false);
                    }
                }));
    }

    private void inflateAppUsageStats(List<AppListModel> res) {
        XLog.d("inflateAppUsageStats");
        ThanosManager thanox = ThanosManager.from(getApplication());
        if (thanox.isServiceInstalled()) {
            Map<String, UsageStats> statsMap = thanox.getUsageStatsManager().queryAndAggregateUsageStats(0, System.currentTimeMillis());
            for (AppListModel app : res) {
                if (statsMap.containsKey(app.appInfo.getPkgName())) {
                    UsageStats stats = statsMap.get(app.appInfo.getPkgName());
                    if (stats != null) {
                        app.lastUsedTimeMills = stats.getLastTimeUsed();
                        app.totalUsedTimeMills = stats.getTotalTimeInForeground();
                    }
                }
            }
        }
    }

    private void registerEventReceivers() {
    }

    private void unRegisterEventReceivers() {
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        CollectionUtils.consumeRemaining(disposables, Disposable::dispose);
        unRegisterEventReceivers();
    }

    public void setAppCategoryFilter(String id) {
        categoryIndex.set(CategoryIndex.from(id));
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit()
                .putString(PREF_KEY_DEF_CATEGORY_ID_PREFIX + featureId, id)
                .apply();
        start();
    }

    List<PackageSet> getAllPackageSetFilterItems() {
        ThanosManager thanox = ThanosManager.from(getApplication());
        if (!thanox.isServiceInstalled()) {
            return Lists.newArrayListWithCapacity(0);
        }
        return thanox.getPkgManager().getAllPackageSets(false);
    }

    PackageSet getCurrentPackageSet() {
        CategoryIndex currentIndex = categoryIndex.get();
        if (currentIndex == null) {
            return null;
        }
        List<PackageSet> all = getAllPackageSetFilterItems();
        if (all.isEmpty()) {
            return null;
        }
        for (PackageSet set : all) {
            if (ObjectsUtils.equals(set.getId(), currentIndex.pkgSetId)) {
                return set;
            }
        }
        return null;
    }

    void clearSearchText() {
        setSearchText(null);
    }

    void setSearchText(String query) {
        queryText.set(query);
        disposables.clear();
        loadModels(true);
    }

    public ObservableBoolean getIsDataLoading() {
        return this.isDataLoading;
    }

    public ObservableArrayList<AppListModel> getListModels() {
        return this.listModels;
    }

    public ObservableField<CategoryIndex> getCategoryIndex() {
        return this.categoryIndex;
    }

    public boolean isSortReverse() {
        return sortReverse.get();
    }

    public void setSortReverse(boolean reverse) {
        sortReverse.set(reverse);
        start();
    }

    public AppSort getCurrentAppSort() {
        return currentSort.get();
    }

    public void setAppSort(AppSort sort) {
        currentSort.set(sort);
        start();
    }

    public void setAppListLoader(ListModelLoader appListLoader) {
        this.appListLoader = appListLoader;
    }

    public interface ListModelLoader {
        @WorkerThread
        List<AppListModel> load(@NonNull CategoryIndex index);
    }
}
