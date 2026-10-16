package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author YJn
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long fId, @PathVariable("isFollow")Boolean isFollow){
        return followService.follow(fId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id")Long fId){
        return followService.isFollow(fId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id")Long fId){
        return followService.followCommons(fId);
    }

}
