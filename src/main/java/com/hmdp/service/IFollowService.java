package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {
    /**
     * 关注或取关用户
     * @param followUserId 被关注的用户ID
     * @param isFollow true表示关注，false表示取关
     * @return 操作结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否已关注指定用户
     * @param followUserId 被查询的用户ID
     * @return 是否已关注
     */
    Result isFollow(Long followUserId);

    /**
     * 获取两个用户之间的共同关注
     * @param id 用户ID
     * @return 共同关注的用户
     */
    Result commonFollows(Long id);
}
