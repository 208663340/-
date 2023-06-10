package net.zjitc.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.zjitc.common.BaseResponse;
import net.zjitc.common.ErrorCode;
import net.zjitc.common.ResultUtils;
import net.zjitc.exception.BusinessException;
import net.zjitc.model.domain.User;
import net.zjitc.model.request.BlogAddRequest;
import net.zjitc.model.request.BlogUpdateRequest;
import net.zjitc.model.vo.BlogVO;
import net.zjitc.service.BlogService;
import net.zjitc.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import static net.zjitc.constants.UserConstants.USER_LOGIN_STATE;

@RestController
@RequestMapping("/blog")
public class BlogController {
    @Resource
    private BlogService blogService;

    @Resource
    private UserService userService;

    @GetMapping("/list")
    public BaseResponse<Page<BlogVO>> listBlogPage(long currentPage, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            return ResultUtils.success(blogService.pageBlog(currentPage, null));
        } else {
            return ResultUtils.success(blogService.pageBlog(currentPage, loginUser.getId()));
        }
    }

    @PostMapping("/add")
    public BaseResponse<String> addBlog(BlogAddRequest blogAddRequest, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        if (StringUtils.isAnyBlank(blogAddRequest.getTitle(), blogAddRequest.getContent())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        blogService.addBlog(blogAddRequest, loginUser);
        return ResultUtils.success("添加成功");
    }

    @GetMapping("/list/my/blog")
    public BaseResponse<Page<BlogVO>> listMyBlogs(long currentPage, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        Page<BlogVO> blogPage = blogService.listMyBlogs(currentPage, loginUser.getId());
        return ResultUtils.success(blogPage);
    }

    @PutMapping("/like/{id}")
    public BaseResponse<String> likeBlog(@PathVariable long id, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        blogService.likeBlog(id, loginUser.getId());
        return ResultUtils.success("成功");
    }

    @GetMapping("/{id}")
    public BaseResponse<BlogVO> getBlogById(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return ResultUtils.success(blogService.getBlogById(id, loginUser.getId()));
    }

    @DeleteMapping("/{id}")
    public BaseResponse<String> deleteBlogById(@PathVariable Long id, HttpServletRequest request) {
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean admin = userService.isAdmin(loginUser);
        blogService.deleteBlog(id, loginUser.getId(), admin);
        return ResultUtils.success("删除成功");
    }

    @PutMapping("/update")
    public BaseResponse<String> updateBlog(BlogUpdateRequest blogUpdateRequest,HttpServletRequest request){
        User loginUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        blogService.updateBlog(blogUpdateRequest,loginUser.getId());
        return ResultUtils.success("更新成功");
    }
}
