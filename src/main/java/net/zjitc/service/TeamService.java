package net.zjitc.service;


import com.baomidou.mybatisplus.extension.service.IService;
import net.zjitc.model.domain.Team;
import net.zjitc.model.domain.User;
import net.zjitc.model.dto.TeamQuery;
import net.zjitc.model.request.TeamJoinRequest;
import net.zjitc.model.request.TeamQuitRequest;
import net.zjitc.model.request.TeamUpdateRequest;
import net.zjitc.model.vo.TeamUserVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
* @author OchiaMalu
* @description 针对表【team(队伍)】的数据库操作Service
* @createDate 2023-05-12 19:33:37
*/
public interface TeamService extends IService<Team> {

    @Transactional(rollbackFor = Exception.class)
    long addTeam(Team team, User loginUser);

    List<TeamUserVO> listTeams(TeamQuery teamQuery, boolean isAdmin);

    boolean updateTeam(TeamUpdateRequest teamUpdateRequest, User loginUser);

    boolean joinTeam(TeamJoinRequest teamJoinRequest, User loginUser);

    @Transactional(rollbackFor = Exception.class)
    boolean quitTeam(TeamQuitRequest teamQuitRequest, User loginUser);

    boolean deleteTeam(long id, User loginUser);
}
