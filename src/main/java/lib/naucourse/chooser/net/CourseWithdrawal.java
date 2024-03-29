package lib.naucourse.chooser.net;

import lib.naucourse.chooser.net.callable.WithdrawalUnitCallable;
import lib.naucourse.chooser.net.school.SchoolClient;
import lib.naucourse.chooser.util.CourseType;
import lib.naucourse.chooser.util.SelectedCourse;
import lib.naucourse.chooser.util.withdrawal.WithdrawalResult;
import lib.naucourse.chooser.util.withdrawal.WithdrawalUnit;
import okhttp3.FormBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class CourseWithdrawal extends CourseSubmit {
    private static final String URL = SchoolClient.JWC_SERVER_URL + "Servlet/DeleteCourseInfo.ashx";

    /**
     * 用于退选课程
     *
     * @param schoolClient 教务系统客户端
     */
    public CourseWithdrawal(SchoolClient schoolClient) {
        super(schoolClient);
    }

    /**
     * 获取课程退选的表单
     *
     * @param courseType     课程类别
     * @param selectedCourse 课程
     * @return 表单
     */
    private static FormBody getPostForm(CourseType courseType, SelectedCourse selectedCourse) {
        FormBody.Builder formBuilder = new FormBody.Builder();
        formBuilder.add("id", selectedCourse.getPostId());
        formBuilder.add("startDate", courseType.getStartDate());
        formBuilder.add("endDate", courseType.getEndDate());
        formBuilder.add("tc", selectedCourse.getPostTc());
        formBuilder.add("c", selectedCourse.getPostc());
        formBuilder.add("tm", selectedCourse.getPostTm());
        return formBuilder.build();
    }

    /**
     * 提交退选课程
     *
     * @param selectedCourseMap    退选课程
     * @param onWithdrawalListener 退选课程监听器
     * @return 是否提交成功（同一时刻只能提交一次）
     */
    synchronized public boolean submit(final Map<CourseType, ArrayList<SelectedCourse>> selectedCourseMap, final OnWithdrawalListener onWithdrawalListener) {
        if (submitLock.tryLock()) {
            if (selectedCourseMap != null) {
                submitResultGetCount = 0;
                pool.submit(() -> {
                    try {
                        ArrayList<Future<WithdrawalResult>> withdrawalSubmitList = new ArrayList<>();
                        for (CourseType courseType : selectedCourseMap.keySet()) {
                            if (stopSubmit) {
                                stopSubmit = false;
                                break;
                            }
                            List<SelectedCourse> selectedCourses = selectedCourseMap.get(courseType);
                            for (SelectedCourse selectedCourse : selectedCourses) {
                                if (stopSubmit) {
                                    stopSubmit = false;
                                    break;
                                }
                                WithdrawalUnit withdrawalUnit = new WithdrawalUnit(selectedCourse, courseType, getPostForm(courseType, selectedCourse), URL);
                                withdrawalSubmitList.add(pool.submit(new WithdrawalUnitCallable(schoolClient, withdrawalUnit)));
                            }
                        }
                        for (Future<WithdrawalResult> future : withdrawalSubmitList) {
                            WithdrawalResult withdrawalResult = null;
                            boolean error = false;
                            try {
                                withdrawalResult = future.get();
                            } catch (InterruptedException ignored) {
                            } catch (ExecutionException e) {
                                future.cancel(true);
                                error = true;
                            }
                            submitResultGetCount++;
                            if (onWithdrawalListener != null) {
                                if (error) {
                                    onWithdrawalListener.onFailed(WithdrawalError.DATA_POST);
                                } else {
                                    onWithdrawalListener.onSubmitSuccess(withdrawalResult);
                                }
                                if (submitResultGetCount >= withdrawalSubmitList.size()) {
                                    System.gc();
                                    onWithdrawalListener.onSubmitFinish();
                                }
                            }
                            if (stopSubmit) {
                                stopSubmit = false;
                                break;
                            }
                        }
                    } finally {
                        submitLock.unlock();
                    }
                });
            } else if (onWithdrawalListener != null) {
                onWithdrawalListener.onFailed(WithdrawalError.COURSE_LIST);
            }
            return true;
        }
        return false;
    }

    public enum WithdrawalError {
        /**
         * 未知错误
         */
        UNKNOWN,
        /**
         * 退选课程列表错误
         */
        COURSE_LIST,
        /**
         * 数据请求错误
         */
        DATA_POST,
        /**
         * 超时错误
         */
        TIME_OUT
    }

    /**
     * 退选课程监听器
     */
    public interface OnWithdrawalListener {
        /**
         * 退选提交成功时的回调
         * 会调用多次
         *
         * @param withdrawalResult 退选结果
         */
        void onSubmitSuccess(WithdrawalResult withdrawalResult);

        /**
         * 退选提交结束时的回调
         * 只会调用一次
         */
        void onSubmitFinish();

        /**
         * 退选发生错误时的回调
         * 会调用多次
         *
         * @param errorCode 错误代码
         */
        void onFailed(WithdrawalError errorCode);
    }
}
