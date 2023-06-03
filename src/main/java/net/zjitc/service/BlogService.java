package net.zjitc.service;

import net.zjitc.model.domain.Blog;
import com.baomidou.mybatisplus.extension.service.IService;
import net.zjitc.model.domain.User;
import net.zjitc.model.request.BlogAddRequest;

/**
* @author OchiaMalu
* @description 针对表【blog】的数据库操作Service
* @createDate 2023-06-03 15:54:34
*/
public interface BlogService extends IService<Blog> {

    Boolean addBlog(BlogAddRequest blogAddRequest, User loginUser);
}
