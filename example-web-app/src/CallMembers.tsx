import { Avatar } from "@stream-io/video-react-sdk";

import type { UserResponse } from "@stream-io/video-react-sdk";

type CallMembersProps = {
  members: UserResponse[];
};
export const CallMembers = ({ members }: CallMembersProps) => {
  return (
    <div>
      {members.map((member) => (
        <div key={member.id}>
          <Avatar name={member.name} imageSrc={member.image} />
          {member.name && (
            <div>
              <span>{member.name}</span>
            </div>
          )}
        </div>
      ))}
    </div>
  );
};