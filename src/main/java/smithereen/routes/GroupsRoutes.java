package smithereen.routes;

import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import smithereen.data.ForeignUser;
import smithereen.exceptions.BadRequestException;
import smithereen.Config;
import smithereen.data.GroupAdmin;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.Utils;
import smithereen.activitypub.ActivityPubWorker;
import smithereen.data.Account;
import smithereen.data.ForeignGroup;
import smithereen.data.Group;
import smithereen.data.Post;
import smithereen.data.SessionInfo;
import smithereen.data.User;
import smithereen.data.UserInteractions;
import smithereen.data.WebDeltaResponseBuilder;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.lang.Lang;
import smithereen.storage.DatabaseUtils;
import smithereen.storage.GroupStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.templates.RenderedTemplateResponse;
import spark.Request;
import spark.Response;
import spark.Session;
import spark.utils.StringUtils;

import static smithereen.Utils.*;

public class GroupsRoutes{

	private static Group getGroup(Request req) throws SQLException{
		int id=parseIntOrDefault(req.params(":id"), 0);
		Group group=GroupStorage.getByID(id);
		if(group==null){
			throw new ObjectNotFoundException("err_group_not_found");
		}
		return group;
	}

	private static Group getGroupAndRequireLevel(Request req, Account self, Group.AdminLevel level) throws SQLException{
		Group group=getGroup(req);
		if(!GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id).isAtLeast(level)){
			throw new UserActionNotAllowedException();
		}
		return group;
	}

	public static Object myGroups(Request req, Response resp, Account self) throws SQLException{
		jsLangKey(req, "cancel", "create");
		RenderedTemplateResponse model=new RenderedTemplateResponse("groups").with("tab", "groups").with("title", lang(req).get("groups"));
		model.with("groups", GroupStorage.getUserGroups(self.user.id));
		model.with("owner", self.user);
		return model.renderToString(req);
	}

	public static Object userGroups(Request req, Response resp) throws SQLException{
		int uid=parseIntOrDefault(req.params(":id"), 0);
		if(uid==0)
			throw new ObjectNotFoundException("err_user_not_found");
		User user=UserStorage.getById(uid);
		if(user==null)
			throw new ObjectNotFoundException("err_user_not_found");
		jsLangKey(req, "cancel", "create");
		RenderedTemplateResponse model=new RenderedTemplateResponse("groups").with("tab", "groups").with("title", lang(req).get("groups"));
		model.with("groups", GroupStorage.getUserGroups(user.id));
		model.with("owner", user);
		return model.renderToString(req);
	}

	public static Object myManagedGroups(Request req, Response resp, Account self) throws SQLException{
		jsLangKey(req, "cancel", "create");
		RenderedTemplateResponse model=new RenderedTemplateResponse("groups").with("tab", "managed").with("title", lang(req).get("groups"));
		model.with("groups", GroupStorage.getUserManagedGroups(self.user.id));
		return model.renderToString(req);
	}

	public static Object createGroup(Request req, Response resp, Account self) throws SQLException{
		RenderedTemplateResponse model=new RenderedTemplateResponse("create_group");
		return wrapForm(req, resp, "create_group", "/my/groups/create", lang(req).get("create_group"), "create", model);
	}

	private static Object groupCreateError(Request req, Response resp, String errKey){
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).show("formMessage_createGroup").setContent("formMessage_createGroup", lang(req).get(errKey)).json();
		}
		RenderedTemplateResponse model=new RenderedTemplateResponse("create_group");
		model.with("groupName", req.queryParams("name")).with("groupUsername", req.queryParams("username"));
		return wrapForm(req, resp, "create_group", "/my/groups/create", lang(req).get("create_group"), "create", model);
	}

	public static Object doCreateGroup(Request req, Response resp, Account self) throws SQLException{
		String username=req.queryParams("username");
		String name=req.queryParams("name");

		if(!isValidUsername(username))
			return groupCreateError(req, resp, "err_group_invalid_username");
		if(isReservedUsername(username))
			return groupCreateError(req, resp, "err_group_reserved_username");

		final int[] id={0};
		boolean r=DatabaseUtils.runWithUniqueUsername(username, ()->{
			id[0]=GroupStorage.createGroup(name, username, self.user.id);
		});

		if(r){
			if(isAjax(req)){
				return new WebDeltaResponseBuilder(resp).replaceLocation("/"+username).json();
			}else{
				resp.redirect(Config.localURI("/"+username).toString());
				return "";
			}
		}else{
			return groupCreateError(req, resp, "err_group_username_taken");
		}
	}

	public static Object groupProfile(Request req, Response resp, Group group) throws SQLException{
		int pageOffset=parseIntOrDefault(req.queryParams("offset"), 0);
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;

		List<User> members=GroupStorage.getRandomMembersForProfile(group.id);
		int[] totalPosts={0};
		List<Post> wall=PostStorage.getWallPosts(group.id, true, 0, 0, pageOffset, totalPosts, false);
		List<Integer> postIDs=wall.stream().map((Post p)->p.id).collect(Collectors.toList());
		HashMap<Integer, UserInteractions> interactions=PostStorage.getPostInteractions(postIDs, self!=null ? self.user.id : 0);

		RenderedTemplateResponse model=new RenderedTemplateResponse("group");
		model.with("group", group).with("members", members).with("postCount", totalPosts[0]).with("pageOffset", pageOffset).with("wall", wall);
		model.with("postInteractions", interactions);
		model.with("title", group.name);
		model.with("admins", GroupStorage.getGroupAdmins(group.id));
		jsLangKey(req, "yes", "no", "cancel", "close", "delete", "post_form_cw", "post_form_cw_placeholder", "attach_menu_photo", "attach_menu_cw");
		if(self!=null){
			Group.AdminLevel level=GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id);
			model.with("membershipState", GroupStorage.getUserMembershipState(group.id, self.user.id));
			model.with("groupAdminLevel", level);
			if(level.isAtLeast(Group.AdminLevel.ADMIN)){
				jsLangKey(req, "update_profile_picture", "save", "profile_pic_select_square_version", "drag_or_choose_file", "choose_file",
						"drop_files_here", "picture_too_wide", "picture_too_narrow", "ok", "error", "error_loading_picture",
						"remove_profile_picture", "confirm_remove_profile_picture", "choose_file_mobile");
			}
		}
		return model.renderToString(req);
	}

	public static Object join(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroup(req);
		ensureUserNotBlocked(self.user, group);
		Group.MembershipState state=GroupStorage.getUserMembershipState(group.id, self.user.id);
		if(state==Group.MembershipState.MEMBER || state==Group.MembershipState.TENTATIVE_MEMBER){
			return wrapError(req, resp, "err_group_already_member");
		}
		GroupStorage.joinGroup(group, self.user.id, false, !(group instanceof ForeignGroup));
		if(group instanceof ForeignGroup){
			ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignGroup) group);
		}
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Config.localURI("/"+group.getFullUsername()).toString());
		return "";
	}

	public static Object leave(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroup(req);
		Group.MembershipState state=GroupStorage.getUserMembershipState(group.id, self.user.id);
		if(state!=Group.MembershipState.MEMBER && state!=Group.MembershipState.TENTATIVE_MEMBER){
			return wrapError(req, resp, "err_group_not_member");
		}
		GroupStorage.leaveGroup(group, self.user.id, state==Group.MembershipState.TENTATIVE_MEMBER);
		if(group instanceof ForeignGroup){
			ActivityPubWorker.getInstance().sendUnfollowActivity(self.user, (ForeignGroup) group);
		}
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Config.localURI("/"+group.getFullUsername()).toString());
		return "";
	}

	public static Object editGeneral(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_general");
		model.with("group", group).with("title", group.name);
		Session s=req.session();
		if(s.attribute("settings.groupEditMessage")!=null){
			model.with("groupEditMessage", s.attribute("settings.groupEditMessage"));
			s.removeAttribute("settings.groupEditMessage");
		}
		return model.renderToString(req);
	}

	public static Object saveGeneral(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		String name=req.queryParams("name"), about=req.queryParams("about");
		String message;
		if(StringUtils.isEmpty(name) || name.length()<1){
			message=lang(req).get("group_name_too_short");
		}else{
			if(StringUtils.isEmpty(about))
				about=null;
			else
				about=preprocessPostHTML(about, null);
			GroupStorage.updateGroupGeneralInfo(group, name, about);
			message=lang(req).get("group_info_updated");
		}
		group=GroupStorage.getByID(group.id);
		ActivityPubWorker.getInstance().sendUpdateGroupActivity(group);
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).show("formMessage_groupEdit").setContent("formMessage_groupEdit", message).json();
		}
		req.session().attribute("settings.groupEditMessage", message);
		resp.redirect("/groups/"+group.id+"/edit");
		return "";
	}

	public static Object members(Request req, Response resp) throws SQLException{
		Group group=getGroup(req);
		int offset=parseIntOrDefault(req.queryParams("offset"), 0);
		List<User> users=GroupStorage.getMembers(group.id, offset, 100);
		RenderedTemplateResponse model=new RenderedTemplateResponse(isAjax(req) ? "user_grid" : "content_wrap").with("users", users);
		model.with("pageOffset", offset).with("total", group.memberCount).with("paginationUrlPrefix", "/groups/"+group.id+"/members?offset=");
//		if(isAjax(req)){
//			if(req.queryParams("fromPagination")==null)
//				return new WebDeltaResponseBuilder(resp).box(lang(req).get("likes_title"), model.renderToString(req), "likesList", 596);
//			else
//				return new WebDeltaResponseBuilder(resp).setContent("likesList", model.renderToString(req));
//		}
		model.with("contentTemplate", "user_grid").with("title", group.name);
		return model.renderToString(req);
	}

	public static Object admins(Request req, Response resp) throws SQLException{
		Group group=getGroup(req);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_admins");
		model.with("admins", GroupStorage.getGroupAdmins(group.id));
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).box(lang(req).get("group_admins"), model.renderContentBlock(req), null, true).json();
		}
		return model.renderToString(req);
	}

	public static Object editAdmins(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_admins");
		model.with("group", group).with("title", group.name);
		model.with("admins", GroupStorage.getGroupAdmins(group.id));
		jsLangKey(req, "cancel", "group_admin_demote", "yes", "no");
		return model.renderToString(req);
	}

	public static Object editMembers(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		Group.AdminLevel level=GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_members");
		int offset=parseIntOrDefault(req.queryParams("offset"), 0);
		List<User> users=GroupStorage.getMembers(group.id, offset, 100);
		model.with("pageOffset", offset).with("total", group.memberCount).with("paginationUrlPrefix", "/groups/"+group.id+"/editMembers?offset=");
		model.with("group", group).with("title", group.name);
		model.with("members", users);
		model.with("adminIDs", GroupStorage.getGroupAdmins(group.id).stream().map(adm->adm.user.id).collect(Collectors.toList()));
		model.with("canAddAdmins", level.isAtLeast(Group.AdminLevel.ADMIN));
		model.with("adminLevel", level);
		jsLangKey(req, "cancel", "yes", "no");
		return model.renderToString(req);
	}

	public static Object editAdminForm(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		int userID=parseIntOrDefault(req.queryParams("id"), 0);
		User user=UserStorage.getById(userID);
		if(user==null)
			throw new ObjectNotFoundException("user_not_found");
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_admin");
		GroupAdmin admin=GroupStorage.getGroupAdmin(group.id, userID);
		model.with("existingAdmin", admin);
		return wrapForm(req, resp, "group_edit_admin", "/groups/"+group.id+"/saveAdmin?id="+userID, user.getFullName(), "save", model);
	}

	public static Object saveAdmin(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		int userID=parseIntOrDefault(req.queryParams("id"), 0);
		User user=UserStorage.getById(userID);
		if(user==null)
			throw new ObjectNotFoundException("user_not_found");

		String _lvl=req.queryParams("level");
		String title=req.queryParams("title");
		Group.AdminLevel lvl=null;
		if(_lvl!=null){
			try{
				lvl=Group.AdminLevel.valueOf(_lvl);
				if(lvl==Group.AdminLevel.OWNER)
					lvl=null;
			}catch(Exception x){
				throw new BadRequestException(x);
			}
		}

		GroupStorage.addOrUpdateGroupAdmin(group.id, userID, title, lvl);

		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Utils.back(req));
		return "";
	}

	public static Object confirmDemoteAdmin(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		int userID=parseIntOrDefault(req.queryParams("id"), 0);
		User user=UserStorage.getById(userID);
		if(user==null)
			throw new ObjectNotFoundException("user_not_found");

		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm").with("message", Utils.lang(req).inflected("group_admin_demote_confirm", user.gender, user.firstName, user.lastName, null)).with("formAction", Config.localURI("/groups/"+group.id+"/removeAdmin?_redir="+URLEncoder.encode(back)+"&id="+userID)).with("back", back).renderToString(req);
	}

	public static Object removeAdmin(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		int userID=parseIntOrDefault(req.queryParams("id"), 0);
		User user=UserStorage.getById(userID);
		if(user==null)
			throw new ObjectNotFoundException("user_not_found");

		GroupStorage.removeGroupAdmin(group.id, userID);

		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Utils.back(req));
		return "";
	}

	public static Object editAdminReorder(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		int userID=parseIntOrDefault(req.queryParams("id"), 0);
		int order=parseIntOrDefault(req.queryParams("order"), 0);
		if(order<0)
			throw new BadRequestException();

		GroupStorage.setGroupAdminOrder(group.id, userID, order);

		return "";
	}

	public static Object blocking(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		Group.AdminLevel level=GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_blocking").with("title", lang(req).get("settings_blocking"));
		model.with("blockedUsers", GroupStorage.getBlockedUsers(group.id));
		model.with("blockedDomains", GroupStorage.getBlockedDomains(group.id));
		model.with("group", group);
		model.with("adminLevel", level);
		jsLangKey(req, "unblock", "yes", "no", "cancel");
		return model.renderToString(req);
	}

	public static Object blockDomainForm(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		RenderedTemplateResponse model=new RenderedTemplateResponse("block_domain");
		return wrapForm(req, resp, "block_domain", "/groups/"+group.id+"/blockDomain", lang(req).get("block_a_domain"), "block", model);
	}

	public static Object blockDomain(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		String domain=req.queryParams("domain");
		if(domain.matches("^([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]{2,}$")){
			if(GroupStorage.isDomainBlocked(group.id, domain))
				return wrapError(req, resp, "err_domain_already_blocked");
			GroupStorage.blockDomain(group.id, domain);
		}
		if(isAjax(req))
			return new WebDeltaResponseBuilder(resp).refresh().json();
		resp.redirect(back(req));
		return "";
	}

	public static Object confirmUnblockDomain(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		String domain=req.queryParams("domain");
		Lang l=Utils.lang(req);
		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm").with("message", l.get("confirm_unblock_domain_X", domain)).with("formAction", "/groups/"+group.id+"/unblockDomain?domain="+domain+"_redir="+URLEncoder.encode(back)).with("back", back).renderToString(req);
	}

	public static Object unblockDomain(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		String domain=req.queryParams("domain");
		if(StringUtils.isNotEmpty(domain))
			GroupStorage.unblockDomain(group.id, domain);
		if(isAjax(req))
			return new WebDeltaResponseBuilder(resp).refresh().json();
		resp.redirect(back(req));
		return "";
	}

	private static User getUserOrThrow(Request req) throws SQLException{
		int id=parseIntOrDefault(req.queryParams("id"), 0);
		if(id==0)
			throw new ObjectNotFoundException("err_user_not_found");
		User user=UserStorage.getById(id);
		if(user==null)
			throw new ObjectNotFoundException("err_user_not_found");
		return user;
	}

	public static Object confirmBlockUser(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		User user=getUserOrThrow(req);
		Lang l=Utils.lang(req);
		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm").with("message", l.inflected("confirm_block_user_X", user.gender, escapeHTML(user.firstName), escapeHTML(user.lastName), null)).with("formAction", "/groups/"+group.id+"/blockUser?id="+user.id+"&_redir="+URLEncoder.encode(back)).with("back", back).renderToString(req);
	}

	public static Object confirmUnblockUser(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		User user=getUserOrThrow(req);
		Lang l=Utils.lang(req);
		String back=Utils.back(req);
		return new RenderedTemplateResponse("generic_confirm").with("message", l.inflected("confirm_unblock_user_X", user.gender, escapeHTML(user.firstName), escapeHTML(user.lastName), null)).with("formAction", "/groups/"+group.id+"/unblockUser?id="+user.id+"&_redir="+URLEncoder.encode(back)).with("back", back).renderToString(req);
	}

	public static Object blockUser(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		User user=getUserOrThrow(req);
		if(GroupStorage.getGroupMemberAdminLevel(group.id, user.id).isAtLeast(Group.AdminLevel.MODERATOR))
			throw new BadRequestException("Can't block a group manager");
		GroupStorage.blockUser(group.id, user.id);
		if(user instanceof ForeignUser)
			ActivityPubWorker.getInstance().sendBlockActivity(group, (ForeignUser) user);
		if(isAjax(req))
			return new WebDeltaResponseBuilder(resp).refresh().json();
		resp.redirect(back(req));
		return "";
	}

	public static Object unblockUser(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.MODERATOR);
		User user=getUserOrThrow(req);
		GroupStorage.unblockUser(group.id, user.id);
		if(user instanceof ForeignUser)
			ActivityPubWorker.getInstance().sendUndoBlockActivity(group, (ForeignUser) user);
		if(isAjax(req))
			return new WebDeltaResponseBuilder(resp).refresh().json();
		resp.redirect(back(req));
		return "";
	}
}
