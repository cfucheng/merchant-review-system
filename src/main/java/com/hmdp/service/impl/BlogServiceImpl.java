package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    /**

    /**
     * 给博客点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 2.判断用户是否已经点过赞了
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3未点赞 可以点赞
            // 3.1数据库字段+1 update set tb_blog liked = liked + 1 where id = id
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2redis集合中添加用户id
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.已点赞 取消点赞
            // 4.1数据库字段-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // 4.2redis集合中移除用户id
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5点赞的用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据id查询用户
        List<User> users = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        List<UserDTO> userDTOS = new ArrayList<>();
        for (User user : users) {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            userDTOS.add(userDTO);
        }
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取当前博主id
        Long id = UserHolder.getUser().getId();
        blog.setUserId(id);
        // 2.新增 探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 3.查询博主的所有粉丝 select * from tb_follow where follow_id(被关注的id) = ?;
        List<Follow> fans = followService.query().eq("follow_user_id", id).list();
        // 4.发送消息给博主的所有粉丝
        for (Follow fan : fans) {
            // 4.1获取到粉丝id
            Long fanId = fan.getUserId();
            // 4.2推送
            String key = FEED_KEY + fanId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(long max, Integer offset) {
        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        // 2.查收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        // 2.1非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 3.实现分页滚动查询 解析数据:blogId(list)、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        // 定义一个最小时间戳和一个为1的偏移量
        long minTime = 0;
        int offSet = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 3.1获取blogId 封装成list集合
            ids.add(Long.valueOf(tuple.getValue()));
            // 3.2获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                offSet++;
            } else {
                minTime = time;
                offSet = 1;
            }
        }
        // 4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        // 3.根据id查询用户
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 5.展示blog 需要有序和点赞此bolg
        for (Blog blog : blogs) {
            // 5.1查询blog有关的用户
            queryBlogUser(blog);
            // 5.2判断用户是否已经点赞
            isBlogLiked(blog);
        }
        // 6.封装数据
        ScrollResult sr = new ScrollResult();
        sr.setList(blogs);
        sr.setOffset(offSet);
        sr.setMinTime(minTime);
        // 7.返回DTO
        return Result.ok(sr);
    }

    /**
     * 查询热门笔记
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询笔记详情
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1.查询bolg
        Blog blog = getById(id);
        // 2.判断笔记是否存在
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 3.查询blog有关的用户
        queryBlogUser(blog);
        // 4.判断用户是否已经点赞
        isBlogLiked(blog);
        return Result.ok(blog);

    }

    private void isBlogLiked(Blog blog) {
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        // 2.判断用户是否已经点过赞了
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 查询blog用户
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
