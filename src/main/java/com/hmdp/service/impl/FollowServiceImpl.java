package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author YJn
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long fId, Boolean isFollow) {
        Long id = UserHolder.getUser().getId();
        String key = "follows:" + id;
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(fId);
            boolean success = save(follow);
            if(success){
                stringRedisTemplate.opsForSet().add(key, fId.toString());
            }
        }else{
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", id).eq("follow_user_id", fId));
            if(success) {
                stringRedisTemplate.opsForSet().remove(key, fId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long fId) {
        Long id = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", id).eq("follow_user_id", fId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long fId) {
        Long id = UserHolder.getUser().getId();
        String k1 = "follows:" + id;
        String k2 = "follows:" + fId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(k1, k2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> dtos = userService
                .listByIds(collect).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }
}
