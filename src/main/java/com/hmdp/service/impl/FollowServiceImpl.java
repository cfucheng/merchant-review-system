package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 关注或取关用户
     * @param followUserId 被关注的用户ID
     * @param isFollow true表示关注，false表示取关
     * @return 操作结果
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断是关注还是取关
        if (isFollow){
            // 2.1关注 写入数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        }else{
            // 2.2取关 删除数据
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        // 4.返回
        return Result.ok();
    }

    /**
     * 判断当前用户是否已关注指定用户
     * @param followUserId 被查询的用户ID
     * @return 是否已关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否已经关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.返回结果
        return Result.ok(count > 0);
    }
}
