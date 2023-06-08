package net.zjitc.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.zjitc.common.BaseResponse;
import net.zjitc.common.ErrorCode;
import net.zjitc.common.ResultUtils;
import net.zjitc.exception.BusinessException;
import net.zjitc.model.domain.BlogComments;
import net.zjitc.model.domain.User;
import net.zjitc.model.request.AddCommentRequest;
import net.zjitc.model.vo.BlogCommentsVO;
import net.zjitc.service.BlogCommentsService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import java.util.List;

import static net.zjitc.constants.UserConstants.USER_LOGIN_STATE;

@RestController
@RequestMapping("/comments")
public class BlogCommentsController {
    @Resource
    private BlogCommentsService blogCommentsService;

    @PostMapping("/add")
    public BaseResponse<String> addComment(@RequestBody AddCommentRequest addCommentRequest, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        if (addCommentRequest.getBlogId() == null || StringUtils.isBlank(addCommentRequest.getContent())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        blogCommentsService.addComment(addCommentRequest, loginUser.getId());
        return ResultUtils.success("添加成功");
    }

    @GetMapping
    public BaseResponse<List<BlogCommentsVO>> listBlogComments(long blogId){
        List<BlogCommentsVO> blogCommentsVOList = blogCommentsService.listComments(blogId);
        return ResultUtils.success(blogCommentsVOList);
    }
}
