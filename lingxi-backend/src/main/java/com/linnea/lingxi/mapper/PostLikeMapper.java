package com.linnea.lingxi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linnea.lingxi.entity.PostLike;
import com.linnea.lingxi.vo.LikeUserVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态点赞表数据访问入口。
 *
 * <p>点赞写入依赖唯一索引 uk_user_post_deleted(user_id, post_id, deleted_id) 兜底并发，
 * 业务层需捕获 DuplicateKeyException 并回退为“已点赞”；取消点赞为带 WHERE 的单行 UPDATE，
 * 不做物理删除，因需要 SET deleted_id = id 的自引用赋值，写在 resources/mapper/PostLikeMapper.xml。</p>
 */
public interface PostLikeMapper extends BaseMapper<PostLike> {

    /**
     * 取消点赞：把有效点赞行置为已取消，并释放活跃唯一位（deleted_id 置为本行ID）。
     *
     * @param userId 点赞用户ID
     * @param postId 被点赞动态ID
     * @return 影响行数；0 表示该用户当前没有有效点赞（未点赞或已取消）
     */
    int cancelLike(@Param("userId") Long userId, @Param("postId") Long postId);

    /**
     * 查询某条动态的点赞人列表（仅有效点赞，按点赞时间倒序）。
     *
     * <p>JOIN user 时带 is_deleted = 0：注销账号不再出现在点赞人列表里（其点赞仍计入总数，
     * 与信息流「作者注销但动态保留」的口径一致，只是不展示人）。</p>
     *
     * @param postId           动态ID
     * @param cursorCreateTime 游标中的点赞时间，首页传 null
     * @param cursorUserId     游标中的点赞人ID（同一动态下同一用户仅一条有效点赞，可作排序第二列），首页传 null
     * @param limit            取行数，调用方传「每页条数 + 1」用于探测下一页
     * @return 点赞人列表，无数据时为空集合
     */
    List<LikeUserVO> selectLikeUserList(@Param("postId") Long postId,
                                        @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                        @Param("cursorUserId") Long cursorUserId,
                                        @Param("limit") int limit);
}
